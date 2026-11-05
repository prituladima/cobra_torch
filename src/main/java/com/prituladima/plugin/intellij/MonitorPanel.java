package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The System Monitor tool window: temperatures, power and disk as numbers; every CPU core, RAM, VRAM and
 * GPU load as charts of the last minute; the processes holding VRAM. About this computer, or about the
 * remote one while connected. Reads once a second, only while the tool window is visible.
 */
final class MonitorPanel extends JPanel implements Disposable {
  private static final Logger LOG = Logger.getInstance(MonitorPanel.class);

  private final Project project;
  private final Content content;
  private final JBLabel stats = new JBLabel();
  private final JPanel body = new JPanel();
  private final ProcessModel processModel = new ProcessModel();
  private final JBTable processTable = new JBTable(processModel);

  private @Nullable ScheduledFuture<?> ticker;
  private @Nullable SystemSampler sampler;
  private @Nullable SystemSampler local;
  private @Nullable String unavailable;   // why this computer's CPU and RAM cannot be read, if so

  // the charts of the current layout, rebuilt when the target or its numbers of cores or GPUs change
  private final List<AreaChart> cores = new ArrayList<>();
  private final List<AreaChart> gpuCharts = new ArrayList<>();
  private @Nullable AreaChart ram, vram, gpuLoad;
  private String layoutKey = "";

  MonitorPanel(Project project, Content content) {
    super(new BorderLayout());
    this.project = project;
    this.content = content;
    stats.setBorder(JBUI.Borders.empty(6, 10, 4, 10));
    stats.setIcon(AllIcons.Toolwindows.ToolWindowProfiler);
    stats.setIconTextGap(JBUI.scale(8));
    add(stats, BorderLayout.NORTH);
    body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
    body.setBorder(JBUI.Borders.empty(0, 6, 8, 6));
    JBScrollPane scroll = new JBScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.setBorder(JBUI.Borders.empty());
    add(scroll, BorderLayout.CENTER);
    processTable.getEmptyText().setText("No process is using the GPU");
    processTable.setShowGrid(false);
    processTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(90));
    processTable.getColumnModel().getColumn(2).setMaxWidth(JBUI.scale(110));
    addHierarchyListener(e -> {
      if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
        if (isShowing()) start();
        else stop();
      }
    });
  }

  private void start() {
    if (ticker != null) return;
    ticker = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
  }

  private void stop() {
    if (ticker != null) ticker.cancel(false);
    ticker = null;
  }

  @Override
  public void dispose() {
    stop();
  }

  /** Background thread: one reading of the computer the toolbar is about. */
  private void tick() {
    try {
      RemoteHost.Remote remote = RemoteHost.connected(project);
      SystemSampler s = sampler;
      boolean switched = remote != null
          ? !(s instanceof SystemSampler.Remote r && r.remote() == remote)
          : !(s instanceof SystemSampler.Local);
      if (switched) s = sampler = remote != null ? new SystemSampler.Remote(remote) : local();
      SystemSampler.Sample sample = s == null ? jmxSample() : s.sample();
      String where = s == null ? "This Computer" : s.where();
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!project.isDisposed()) apply(where, sample);
      }, ModalityState.any());
    } catch (Exception | LinkageError e) {
      LOG.info("System monitor reading: " + e);
      if (e instanceof LinkageError) {
        unavailable = e.toString();
        local = null;
        sampler = null;
      }
    }
  }

  private @Nullable SystemSampler local() {
    if (local == null && unavailable == null) {
      try {
        local = new SystemSampler.Local();
      } catch (Exception | LinkageError e) {   // OSHI's native access failed: the JVM's own numbers instead
        unavailable = e.toString();
        LOG.warn("System monitor: OSHI unavailable, CPU per core and temperatures not shown", e);
      }
    }
    return local;
  }

  /** Without OSHI: one CPU chart for all cores, RAM from the JVM; the GPU still from nvidia-smi. */
  private static SystemSampler.Sample jmxSample() {
    com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    double load = os.getCpuLoad();
    List<SystemSampler.Gpu> gpus = SystemSampler.parseGpus(GpuInfo.nvidiaSmi(SystemSampler.GPU_QUERY));
    return new SystemSampler.Sample(System.currentTimeMillis(), load < 0 ? null : new double[] {load},
        os.getTotalMemorySize() - os.getFreeMemorySize(), os.getTotalMemorySize(), null, gpus, List.of(), 0, 0);
  }

  // ---- EDT -----------------------------------------------------------------------------------------------

  private void apply(String where, SystemSampler.Sample s) {
    int coreCount = s.cores() == null ? cores.size() : s.cores().length;
    String key = where + "|" + coreCount + "|" + s.gpus().size();
    if (!key.equals(layoutKey)) rebuild(where, coreCount, s.gpus().size(), key);

    long t = s.time();
    if (s.cores() != null && s.cores().length == cores.size()) {
      for (int i = 0; i < cores.size(); i++) cores.get(i).add(t, s.cores()[i] * 100, 100, pct(s.cores()[i] * 100));
    }
    if (ram != null && s.ramTotal() > 0) ram.add(t, s.ramUsed(), s.ramTotal(), gb(s.ramUsed()) + " of " + gb(s.ramTotal()));
    List<SystemSampler.Gpu> gpus = s.gpus();
    if (!gpus.isEmpty() && vram != null && gpuLoad != null) {
      double used = 0, total = 0, load = 0;
      for (SystemSampler.Gpu g : gpus) {
        used += nz(g.memUsed());
        total += nz(g.memTotal());
        load += nz(g.load());
      }
      load /= gpus.size();
      vram.add(t, used, total, mib(used) + " of " + mib(total));
      gpuLoad.add(t, load, 100, pct(load) + (gpus.size() > 1 ? " on average" : ""));
      for (int i = 0; i < gpuCharts.size() && i < gpus.size(); i++) {
        SystemSampler.Gpu g = gpus.get(i);
        gpuCharts.get(i).add(t, nz(g.load()), 100, pct(nz(g.load())) + " · " + mib(nz(g.memUsed())) + " of " + mib(nz(g.memTotal())));
      }
    }
    stats.setText(statsHtml(where, s));
    processModel.set(s.processes());
  }

  private void rebuild(String where, int coreCount, int gpuCount, String key) {
    layoutKey = key;
    content.setDisplayName(where);
    body.removeAll();
    cores.clear();
    gpuCharts.clear();

    if (coreCount > 0) {
      body.add(section(coreCount == 1 && unavailable != null ? "CPU, all cores" : "CPU, " + coreCount + " cores"));
      int cols = coreCount <= 8 ? coreCount : coreCount <= 32 ? 8 : 16;
      JPanel grid = new JPanel(new GridLayout(0, cols, JBUI.scale(6), JBUI.scale(4)));
      for (int i = 0; i < coreCount; i++) {
        AreaChart c = new AreaChart(coreCount == 1 ? "CPU" : "CPU " + i, AreaChart.BLUE, MonitorPanel::pct, true);
        cores.add(c);
        grid.add(c);
      }
      body.add(row(grid));
    }

    body.add(section(gpuCount == 0 ? "Memory" : "Memory and GPU"));
    ram = new AreaChart("RAM", AreaChart.ORANGE, MonitorPanel::gb, false);
    JPanel main = new JPanel(new GridLayout(1, 0, JBUI.scale(10), 0));
    main.add(ram);
    vram = gpuLoad = null;
    if (gpuCount > 0) {
      vram = new AreaChart(gpuCount > 1 ? "VRAM, " + gpuCount + " GPUs" : "VRAM", AreaChart.AQUA, MonitorPanel::mib, false);
      gpuLoad = new AreaChart("GPU load", AreaChart.YELLOW, MonitorPanel::pct, false);
      main.add(vram);
      main.add(gpuLoad);
    }
    body.add(row(main));

    if (gpuCount > 1) {
      body.add(section("Each GPU: load, and its VRAM"));
      JPanel grid = new JPanel(new GridLayout(0, Math.min(gpuCount, 4), JBUI.scale(6), JBUI.scale(4)));
      for (int i = 0; i < gpuCount; i++) {
        AreaChart c = new AreaChart("GPU " + i, AreaChart.YELLOW, MonitorPanel::pct, true);
        gpuCharts.add(c);
        grid.add(c);
      }
      body.add(row(grid));
    }

    if (gpuCount > 0) {
      body.add(section("Processes using the GPU"));
      JPanel table = new JPanel(new BorderLayout());
      table.add(processTable.getTableHeader(), BorderLayout.NORTH);
      table.add(processTable, BorderLayout.CENTER);
      body.add(row(table));
    }
    body.revalidate();
    body.repaint();
  }

  private static JComponent section(String title) {
    JBLabel label = new JBLabel(title);
    label.setFont(JBFont.small());
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setBorder(JBUI.Borders.empty(8, 4, 2, 4));
    return row(label);
  }

  /** Full width in the vertical box, at its own preferred height. */
  private static JComponent row(JComponent c) {
    JPanel p = new JPanel(new BorderLayout()) {
      @Override
      public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
      }
    };
    p.add(c, BorderLayout.CENTER);
    p.setAlignmentX(Component.LEFT_ALIGNMENT);
    return p;
  }

  /** "This Computer · CPU 54 °C · GPU 47 °C · GPU power 19.6 of 145 W · Disk 261 GB free of 915 GB" */
  private String statsHtml(String where, SystemSampler.Sample s) {
    String muted = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground());
    StringBuilder sb = new StringBuilder("<html><b>").append(escape(where)).append("</b>");
    item(sb, muted, "CPU", s.cpuTemp() == null ? "—" : String.format(Locale.ROOT, "%.0f °C", s.cpuTemp()));
    if (!s.gpus().isEmpty()) {
      double min = Double.MAX_VALUE, max = -1, power = 0, limit = 0;
      for (SystemSampler.Gpu g : s.gpus()) {
        if (!Double.isNaN(g.temp())) {
          min = Math.min(min, g.temp());
          max = Math.max(max, g.temp());
        }
        power += nz(g.power());
        limit += nz(g.powerLimit());
      }
      String temp = max < 0 ? "—" : min == max ? String.format(Locale.ROOT, "%.0f °C", max) : String.format(Locale.ROOT, "%.0f–%.0f °C", min, max);
      item(sb, muted, s.gpus().size() > 1 ? "GPUs" : "GPU", temp);
      item(sb, muted, "GPU power", power <= 0 ? "—" : limit > 0
          ? String.format(Locale.ROOT, "%.0f of %.0f W", power, limit) : String.format(Locale.ROOT, "%.0f W", power));
    } else {
      item(sb, muted, "GPU", "no NVIDIA GPU");
    }
    if (s.diskTotal() > 0) item(sb, muted, "Disk", gb(s.diskFree()) + " free of " + gb(s.diskTotal()));
    return sb.append("</html>").toString();
  }

  private static void item(StringBuilder sb, String muted, String label, String value) {
    sb.append("&nbsp;&nbsp;&nbsp;<font color='").append(muted).append("'>").append(escape(label)).append("</font>&nbsp;")
        .append(escape(value).replace(" ", "&nbsp;"));
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static double nz(double v) {
    return Double.isNaN(v) ? 0 : v;
  }

  static String pct(double v) {
    return String.format(Locale.ROOT, "%.0f%%", v);
  }

  static String gb(double bytes) {
    return CondaEnvSizes.humanSize((long) bytes);
  }

  static String mib(double mib) {
    return CondaEnvSizes.humanSize((long) (mib * 1024 * 1024));
  }

  /** PID | process | VRAM, from nvidia-smi's compute apps. */
  private static final class ProcessModel extends AbstractTableModel {
    private List<SystemSampler.GpuProcess> rows = List.of();

    void set(List<SystemSampler.GpuProcess> processes) {
      if (Objects.equals(rows, processes)) return;
      rows = List.copyOf(processes);
      fireTableDataChanged();
    }

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
        case 0 -> "PID";
        case 1 -> "Process";
        default -> "VRAM";
      };
    }

    @Override
    public Object getValueAt(int row, int column) {
      SystemSampler.GpuProcess p = rows.get(row);
      return switch (column) {
        case 0 -> p.pid();
        case 1 -> p.name();
        default -> Double.isNaN(p.vramMiB()) ? "—" : mib(p.vramMiB());
      };
    }
  }
}
