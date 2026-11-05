package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Every conda environment of this computer, or of the connected remote one: name | size | remove.
 * Not modal, so conda's [y]/n question in the terminal (or console) the remove opens can be answered;
 * a removed environment leaves the list once its folder is gone. Sizes count every file of the folder,
 * so packages hard-linked into several environments count in each.
 */
final class CondaEnvListDialog extends DialogWrapper {
  private static final Logger LOG = Logger.getInstance(CondaEnvListDialog.class);
  private static final int NAME = 0, SIZE = 1, REMOVE = 2;

  /** An environment: its name, its folder, and whether it is a conda installation's base, which conda keeps. */
  record Row(String name, String prefix, boolean base) {}

  /** Where the environments are. */
  interface Source {
    String where();

    List<Row> list() throws Exception;

    long size(Row row) throws Exception;

    boolean exists(Row row) throws Exception;

    void remove(Project project, Row row);
  }

  private final Project project;
  private final Source source;
  private final Model model = new Model();
  private final JBLabel total = new JBLabel("Listing environments…");
  private final Set<Row> removing = ConcurrentHashMap.newKeySet();

  static void show(Project project) {
    RemoteHost.Remote remote = RemoteHost.connected(project);
    new CondaEnvListDialog(project, remote != null ? remote : new Local()).show();
  }

  private CondaEnvListDialog(Project project, Source source) {
    super(project, true, IdeModalityType.MODELESS);
    this.project = project;
    this.source = source;
    setTitle("Conda Environments on " + source.where());
    setOKButtonText("Close");
    init();
    load();
    Timer timer = new Timer(3000, e -> checkRemoved());
    timer.start();
    Disposer.register(getDisposable(), timer::stop);
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {getOKAction()};
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBTable table = new JBTable(model);
    table.setShowGrid(false);
    table.setRowHeight(JBUI.scale(24));
    table.getColumnModel().getColumn(SIZE).setCellRenderer(new SizeRenderer());
    table.getColumnModel().getColumn(REMOVE).setCellRenderer(new RemoveRenderer());
    table.getColumnModel().getColumn(SIZE).setPreferredWidth(JBUI.scale(110));
    table.getColumnModel().getColumn(SIZE).setMaxWidth(JBUI.scale(140));
    table.getColumnModel().getColumn(REMOVE).setPreferredWidth(JBUI.scale(36));
    table.getColumnModel().getColumn(REMOVE).setMaxWidth(JBUI.scale(36));
    TableRowSorter<Model> sorter = new TableRowSorter<>(model);
    sorter.setComparator(SIZE, Comparator.nullsFirst(Comparator.<Long>naturalOrder()));
    sorter.setSortable(REMOVE, false);
    table.setRowSorter(sorter);

    MouseAdapter clicks = new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        Row row = removableAt(table, e);
        if (row == null) return;
        removing.add(row);
        source.remove(project, row);
        model.fireTableDataChanged();
      }

      @Override
      public void mouseMoved(MouseEvent e) {
        table.setCursor(removableAt(table, e) != null ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
      }
    };
    table.addMouseListener(clicks);
    table.addMouseMotionListener(clicks);

    total.setForeground(UIUtil.getContextHelpForeground());
    total.setToolTipText("Packages that conda hard-links into several environments count in each, "
        + "so the total can be more than the disk space they use.");
    JBScrollPane scroll = new JBScrollPane(table);
    scroll.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(420)));
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(total, BorderLayout.SOUTH);
    return panel;
  }

  private @Nullable Row removableAt(JTable table, MouseEvent e) {
    int viewRow = table.rowAtPoint(e.getPoint());
    if (viewRow < 0 || table.columnAtPoint(e.getPoint()) != REMOVE) return null;
    Row row = model.rows.get(table.convertRowIndexToModel(viewRow));
    return row.base() || removing.contains(row) ? null : row;
  }

  /** The list first, then the sizes one by one as they are measured. */
  private void load() {
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      List<Row> rows;
      try {
        rows = source.list();
      } catch (Exception e) {
        LOG.info("Listing conda environments on " + source.where() + ": " + e.getMessage());
        rows = List.of();
      }
      List<Row> found = rows;
      later(() -> {
        model.rows.addAll(found);
        model.fireTableDataChanged();
        updateTotal();
      });
      for (Row row : found) {
        if (isDisposed()) return;
        try {
          long size = source.size(row);
          later(() -> {
            model.sizes.put(row, size);
            int i = model.rows.indexOf(row);
            if (i >= 0) model.fireTableRowsUpdated(i, i);
            updateTotal();
          });
        } catch (Exception e) {
          LOG.info("Measuring " + row.prefix() + ": " + e.getMessage());
        }
      }
    });
  }

  /** Drops the environments whose removal was asked for, once their folder is gone. */
  private void checkRemoved() {
    if (removing.isEmpty()) return;
    List<Row> asked = new ArrayList<>(removing);
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      Set<Row> gone = new HashSet<>();
      for (Row row : asked) {
        try {
          if (!source.exists(row)) gone.add(row);
        } catch (Exception ignored) {
          // check again next time
        }
      }
      if (gone.isEmpty()) return;
      later(() -> {
        removing.removeAll(gone);
        model.rows.removeAll(gone);
        gone.forEach(model.sizes::remove);
        model.fireTableDataChanged();
        updateTotal();
      });
    });
  }

  private void updateTotal() {
    long sum = model.sizes.values().stream().mapToLong(Long::longValue).sum();
    int n = model.rows.size();
    String measured = model.sizes.size() < n ? ", measuring…" : "";
    total.setText(n + (n == 1 ? " environment, " : " environments, ") + CondaEnvSizes.humanSize(sum) + " in total" + measured);
  }

  private void later(Runnable r) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (!isDisposed()) r.run();
    }, ModalityState.any());
  }

  private final class Model extends AbstractTableModel {
    final List<Row> rows = new ArrayList<>();
    final Map<Row, Long> sizes = new ConcurrentHashMap<>();

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return 3;
    }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case NAME -> "Environment";
        case SIZE -> "Size";
        default -> "";
      };
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return column == SIZE ? Long.class : column == NAME ? String.class : Row.class;
    }

    @Override
    public Object getValueAt(int r, int column) {
      Row row = rows.get(r);
      return switch (column) {
        case NAME -> row.name();
        case SIZE -> sizes.get(row);
        default -> row;
      };
    }
  }

  private static final class SizeRenderer extends DefaultTableCellRenderer {
    SizeRenderer() {
      setHorizontalAlignment(SwingConstants.RIGHT);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
      super.getTableCellRendererComponent(table, value, selected, focus, row, col);
      setText(value == null ? "…" : CondaEnvSizes.humanSize((Long) value));
      return this;
    }
  }

  private final class RemoveRenderer extends DefaultTableCellRenderer {
    RemoveRenderer() {
      setHorizontalAlignment(SwingConstants.CENTER);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int col) {
      super.getTableCellRendererComponent(table, null, selected, false, row, col);
      Row r = (Row) value;
      Icon icon = r.base() ? null : removing.contains(r) ? AllIcons.Process.Step_1 : AllIcons.Actions.GC;
      setIcon(icon);
      setText("");
      setToolTipText(r.base() ? "conda keeps its base environment" : removing.contains(r) ? "Removing…" : "Remove " + r.name());
      return this;
    }
  }

  /** This computer's environments; remove runs conda in a new Terminal tab. */
  private static final class Local implements Source {
    @Override
    public String where() {
      return "This Computer";
    }

    @Override
    public List<Row> list() {
      List<Row> rows = new ArrayList<>();
      for (Path p : CondaEnvLocator.all()) {
        boolean base = CondaEnvLocator.isRoot(p);
        rows.add(new Row(base ? "base" : String.valueOf(p.getFileName()), p.toString(), base));
      }
      return rows;
    }

    @Override
    public long size(Row row) {
      return CondaEnvSizes.folderSize(Path.of(row.prefix()));
    }

    @Override
    public boolean exists(Row row) {
      return Files.isDirectory(Path.of(row.prefix()).resolve("conda-meta"));
    }

    @Override
    public void remove(Project project, Row row) {
      CondaEnv.runInTerminal(project, System.getProperty("user.home"), "conda remove " + row.name(),
          "conda env remove -p " + CondaEnv.quote(row.prefix()));
    }
  }
}
