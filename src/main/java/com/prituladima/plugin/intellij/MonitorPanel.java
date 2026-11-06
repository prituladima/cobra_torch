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
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.HierarchyEvent;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleFunction;

/**
 * The System Monitor tool window, about this computer or the connected remote one; what it shows is set in
 * Settings | Tools | Cobra torch ({@link CobraTorchSettings}). From the top: the computer (and, while
 * connected, its price and the cost so far); the CPU: its name, power and temperature, and the load of each
 * core; the GPUs: their name, the power and temperature of each, the VRAM of each, the load of each; RAM;
 * the free disk space; the processes holding VRAM. Loads and memory are graphs of the last minute or bars
 * of now. Reads once a second, only while the tool window is visible.
 */
final class MonitorPanel extends JPanel implements Disposable {
  private static final Logger LOG = Logger.getInstance(MonitorPanel.class);
  private static final int GPUS_PER_LINE = 4;

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

  // the parts of the current layout, rebuilt when the computer, its cores or GPUs, or the settings change
  private final List<Meter> cores = new ArrayList<>();
  private final List<Meter> vrams = new ArrayList<>();   // one per GPU
  private final List<Meter> loads = new ArrayList<>();   // one per GPU
  private final List<JBLabel> gpuFacts = new ArrayList<>();   // power and temperature, one per GPU
  private @Nullable JBLabel cpuLine, gpuLine, storage;
  private @Nullable AreaChart ram;
  private CobraTorchSettings.Values settings = CobraTorchSettings.get();
  private String layoutKey = "";

  /** One reading and what goes with it: where it is from, the price while connected, the GPUs' names and driver. */
  private record Reading(String where, @Nullable String price, SystemSampler.Sample sample, @Nullable GpuInfo.Gpu gpuInfo) {}

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
    processTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(50));
    processTable.getColumnModel().getColumn(1).setMaxWidth(JBUI.scale(90));
    processTable.getColumnModel().getColumn(3).setMaxWidth(JBUI.scale(110));
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
      Reading reading = s instanceof SystemSampler.Remote r
          ? new Reading(r.where(), r.remote().price(), sample, r.remote().gpu())
          : new Reading(s == null ? "This Computer" : s.where(), null, sample, GpuInfo.get());
      ApplicationManager.getApplication().invokeLater(() -> {
        if (!project.isDisposed()) apply(reading);
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

  /** Without OSHI: one CPU chart for all cores, RAM from the JVM; the GPUs still from nvidia-smi. */
  private static SystemSampler.Sample jmxSample() {
    com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    double load = os.getCpuLoad();
    List<SystemSampler.Gpu> gpus = SystemSampler.parseGpus(GpuInfo.nvidiaSmi(SystemSampler.GPU_QUERY));
    List<SystemSampler.GpuProcess> processes = gpus.isEmpty() ? List.of()
        : SystemSampler.parseProcesses(GpuInfo.nvidiaSmi(SystemSampler.APPS_QUERY), gpus);
    long[] disk = SystemSampler.homeDisk();
    SystemSampler.Cpu cpu = new SystemSampler.Cpu(SystemSampler.blankToNull(System.getenv("PROCESSOR_IDENTIFIER")), null,
        Double.NaN, Double.NaN, "unknown", "The CPU's power is not known without OSHI");
    return new SystemSampler.Sample(System.currentTimeMillis(), load < 0 ? null : new double[] {load}, cpu,
        os.getTotalMemorySize() - os.getFreeMemorySize(), os.getTotalMemorySize(), null, gpus, processes, disk[0], disk[1]);
  }

  // ---- EDT -----------------------------------------------------------------------------------------------

  private void apply(Reading r) {
    SystemSampler.Sample s = r.sample();
    CobraTorchSettings.Values set = CobraTorchSettings.get();
    int coreCount = s.cores() == null ? cores.size() : s.cores().length;
    List<SystemSampler.Gpu> gpus = s.gpus();
    String key = r.where() + "|" + coreCount + "|" + gpus.stream().map(g -> g.index() + " " + g.model()).toList() + "|"
        + (s.diskTotal() > 0) + "|" + set.key();
    if (!key.equals(layoutKey)) rebuild(r, coreCount, set, key);

    long t = s.time();
    stats.setText(statsHtml(r));
    if (cpuLine != null) {
      cpuLine.setText(cpuHtml(s));
      SystemSampler.Cpu cpu = s.cpu();
      cpuLine.setToolTipText(settings.cpuPower && Double.isNaN(cpu.watts()) ? cpu.powerNote() : null);
      cpuLine.setIcon(settings.cpuName ? CondaIcons.cpu(cpu.vendor(), cpu.name()) : null);
    }
    if (s.cores() != null && s.cores().length == cores.size()) {
      for (int i = 0; i < cores.size(); i++) cores.get(i).add(t, s.cores()[i] * 100, 100, pct(s.cores()[i] * 100));
    }
    if (gpuLine != null) gpuLine.setText(gpuNameHtml(r));
    for (int i = 0; i < gpus.size(); i++) {
      SystemSampler.Gpu g = gpus.get(i);
      double used = nz(g.memUsed()), total = nz(g.memTotal()), load = nz(g.load());
      if (i < vrams.size()) vrams.get(i).add(t, used, total, mib(used) + " of " + mib(total));
      if (i < loads.size()) loads.get(i).add(t, load, 100, pct(load));
      if (i < gpuFacts.size()) gpuFacts.get(i).setText(gpuFactsHtml(g, gpus.size()));
    }
    if (ram != null && s.ramTotal() > 0) ram.add(t, s.ramUsed(), s.ramTotal(), gb(s.ramUsed()) + " of " + gb(s.ramTotal()));
    if (storage != null) storage.setText(storageHtml(s));
    processModel.set(s.processes());
  }

  /** The parts the settings ask for, in the order of the class comment. */
  private void rebuild(Reading r, int coreCount, CobraTorchSettings.Values set, String key) {
    layoutKey = key;
    settings = set;
    content.setDisplayName(r.where());
    body.removeAll();
    cores.clear();
    vrams.clear();
    loads.clear();
    gpuFacts.clear();
    cpuLine = gpuLine = storage = null;
    List<SystemSampler.Gpu> gpus = r.sample().gpus();

    if (set.showCpu) {
      body.add(section(coreCount == 1 && unavailable != null ? "CPU, all cores" : coreCount > 0 ? "CPU, " + coreCount + " cores" : "CPU"));
      if (set.cpuName || set.cpuPower || set.cpuTemperature) {
        cpuLine = text();
        body.add(row(cpuLine));
      }
      if (set.cpuCores && coreCount > 0) {
        int cols = Math.min(coreCount, set.perLine());
        JPanel grid = new JPanel(new GridLayout(0, cols, JBUI.scale(6), JBUI.scale(4)));
        for (int i = 0; i < coreCount; i++) {
          // more than 8 a line leaves room for the number only: "12  64%"
          String name = coreCount == 1 ? "CPU" : cols > 8 ? String.valueOf(i) : "CPU " + i;
          Meter m = meter(name, AreaChart.BLUE, MonitorPanel::pct, true, set.coresAsBars);
          cores.add(m);
          grid.add((JComponent) m);
        }
        body.add(row(grid));
      }
    }

    if (set.showGpu) {
      int n = gpus.size(), cols = Math.max(1, Math.min(n, GPUS_PER_LINE));
      body.add(section(n > 1 ? n + " GPUs" : "GPU"));
      if (n == 0) {
        JBLabel none = text();
        none.setText("No NVIDIA GPU");
        none.setForeground(UIUtil.getContextHelpForeground());
        body.add(row(none));
      } else {
        if (set.gpuName) {
          gpuLine = text();
          gpuLine.setIcon(CondaIcons.NVIDIA);
          body.add(row(gpuLine));
        }
        if (set.gpuPower || set.gpuTemperature) {
          for (int from = 0; from < n; from += cols) {
            JPanel line = new JPanel(new GridLayout(1, cols, JBUI.scale(n > 4 ? 6 : 10), 0));
            for (SystemSampler.Gpu ignored : gpus.subList(from, Math.min(n, from + cols))) {
              JBLabel l = text();
              gpuFacts.add(l);
              line.add(l);
            }
            for (int i = Math.min(n, from + cols) - from; i < cols; i++) line.add(new JPanel());
            body.add(row(line));
          }
        }
        if (set.gpuVram) {
          body.add(section(n > 1 ? "VRAM, each GPU" : "VRAM"));
          for (int from = 0; from < n; from += cols) {
            body.add(row(gpuLine(gpus, gpus.subList(from, Math.min(n, from + cols)), cols, vrams, "VRAM", AreaChart.AQUA,
                MonitorPanel::mib, set.vramAsBars)));
          }
        }
        if (set.gpuLoad) {
          body.add(section(n > 1 ? "GPU load, each GPU" : "GPU load"));
          for (int from = 0; from < n; from += cols) {
            body.add(row(gpuLine(gpus, gpus.subList(from, Math.min(n, from + cols)), cols, loads, "GPU load", AreaChart.YELLOW,
                MonitorPanel::pct, set.loadAsBars)));
          }
        }
      }
    }

    body.add(section("RAM"));
    ram = new AreaChart("RAM", AreaChart.ORANGE, MonitorPanel::gb, false);
    body.add(row(ram));

    if (set.showStorage && r.sample().diskTotal() > 0) {
      body.add(section("Storage"));
      storage = text();
      storage.setToolTipText("The disk of the home folder");
      body.add(row(storage));
    }

    if (set.showGpu && set.gpuProcesses && !gpus.isEmpty()) {
      body.add(section("Processes using the GPU"));
      JPanel table = new JPanel(new BorderLayout());
      table.add(processTable.getTableHeader(), BorderLayout.NORTH);
      table.add(processTable, BorderLayout.CENTER);
      body.add(row(table));
    }
    body.revalidate();
    body.repaint();
  }

  /** A graph of the last minute in {@code color}, or a green bar of now of the same size. */
  private static Meter meter(String name, Color color, DoubleFunction<String> format, boolean compact, boolean bar) {
    return bar ? new BarMeter(name, format, compact) : new AreaChart(name, color, format, compact);
  }

  /**
   * One line of {@code cols} meters, one of each GPU of {@code line}, named "GPU 0", "GPU 1", … ({@code single}
   * with one GPU only); empty places at the end keep the columns in line with the line above. Full size up to
   * 4 GPUs in all, small beyond. With different models, each meter's tooltip names its GPU.
   */
  private static JPanel gpuLine(List<SystemSampler.Gpu> all, List<SystemSampler.Gpu> line, int cols, List<Meter> meters,
                                String single, Color color, DoubleFunction<String> format, boolean bar) {
    int n = all.size();
    boolean mixed = all.stream().map(SystemSampler.Gpu::model).distinct().count() > 1;
    JPanel panel = new JPanel(new GridLayout(1, cols, JBUI.scale(n > 4 ? 6 : 10), 0));
    panel.setBorder(JBUI.Borders.emptyTop(4));
    for (SystemSampler.Gpu g : line) {
      Meter m = meter(n == 1 ? single : "GPU " + g.index(), color, format, n > 4, bar);
      if (mixed) ((JComponent) m).setToolTipText("GPU " + g.index() + ": NVIDIA " + g.model());
      meters.add(m);
      panel.add((JComponent) m);
    }
    for (int i = line.size(); i < cols; i++) panel.add(new JPanel());
    return panel;
  }

  private static JComponent section(String title) {
    JBLabel label = new JBLabel(title);
    label.setFont(JBFont.small());
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setBorder(JBUI.Borders.empty(8, 4, 2, 4));
    return row(label);
  }

  /** A line of text with its facts, like "Intel Core i5-14400F · power 54 of 65 W · 45 °C". */
  private static JBLabel text() {
    JBLabel label = new JBLabel();
    label.setBorder(JBUI.Borders.empty(2, 4));
    label.setIconTextGap(JBUI.scale(6));
    return label;
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

  // ---- the text lines --------------------------------------------------------------------------------------

  /** "This Computer", or "root@ssh5.vast.ai:41234 · $2.50 per hour, $1.04 so far". */
  private static String statsHtml(Reading r) {
    StringBuilder sb = new StringBuilder("<html><b>").append(escape(r.where())).append("</b>");
    if (r.price() != null) item(sb, "Price", r.price());
    return sb.append("</html>").toString();
  }

  /** "[intel] Intel Core i5-14400F · power 54 of 65 W · temperature 45 °C", as the settings ask. */
  private String cpuHtml(SystemSampler.Sample s) {
    SystemSampler.Cpu cpu = s.cpu();
    StringBuilder sb = new StringBuilder("<html>");
    if (settings.cpuName) sb.append("<b>").append(escape(cpu.name() == null ? "CPU" : cleanCpuName(cpu.name()))).append("</b>");
    else sb.append(muted("CPU"));
    if (settings.cpuPower) {
      item(sb, "power", watts(cpu.watts(), cpu.limitWatts()));
      if (Double.isNaN(cpu.watts()) && cpu.powerWhy() != null) sb.append("&nbsp;").append(muted("(" + cpu.powerWhy() + ")"));
    }
    if (settings.cpuTemperature) item(sb, "temperature", s.cpuTemp() == null ? "—" : String.format(Locale.ROOT, "%.0f °C", s.cpuTemp()));
    return sb.append("</html>").toString();
  }

  /** "[nvidia] 8 × H100 80GB HBM3 · VRAM 640 GB · CUDA 12.8 · driver 570.124.06". */
  private static String gpuNameHtml(Reading r) {
    GpuInfo.Gpu info = r.gpuInfo();
    StringBuilder sb = new StringBuilder("<html><b>");
    if (info == null) {
      List<SystemSampler.Gpu> gpus = r.sample().gpus();
      return sb.append(escape(GpuInfo.groupedName(gpus.stream().map(SystemSampler.Gpu::model).toList()))).append("</b></html>").toString();
    }
    sb.append(escape(info.name())).append("</b>");
    item(sb, null, info.vram());
    if (info.cudaVersion() != null) item(sb, null, info.cudaVersion());
    item(sb, "driver", info.driver());
    return sb.append("</html>").toString();
  }

  /** "GPU 0  power 250 of 700 W · 40 °C"; without "GPU 0" for one GPU. */
  private String gpuFactsHtml(SystemSampler.Gpu g, int count) {
    StringBuilder sb = new StringBuilder("<html>");
    boolean first = true;
    if (count > 1) {
      sb.append(muted("GPU " + g.index())).append("&nbsp;&nbsp;");
    }
    if (settings.gpuPower) {
      sb.append(muted("power")).append("&nbsp;").append(nbsp(watts(g.power(), g.powerLimit())));
      first = false;
    }
    if (settings.gpuTemperature) {
      if (!first) sb.append("&nbsp;·&nbsp;");
      if (first) sb.append(muted("temperature")).append("&nbsp;");
      sb.append(Double.isNaN(g.temp()) ? "—" : String.format(Locale.ROOT, "%.0f&nbsp;°C", g.temp()));
    }
    return sb.append("</html>").toString();
  }

  /** "Disk  299.9 GB free of 914.6 GB". */
  private static String storageHtml(SystemSampler.Sample s) {
    return "<html>" + muted("Disk") + "&nbsp;&nbsp;<b>" + nbsp(gb(s.diskFree())) + "</b> free of " + nbsp(gb(s.diskTotal())) + "</html>";
  }

  /** "54 of 65 W", "54 W", "— of 65 W", "—". */
  private static String watts(double watts, double limit) {
    String w = Double.isNaN(watts) ? "—" : String.format(Locale.ROOT, "%.0f", watts);
    return Double.isNaN(limit) || limit <= 0 ? (Double.isNaN(watts) ? "—" : w + " W") : w + " of " + String.format(Locale.ROOT, "%.0f W", limit);
  }

  /** {@code Intel(R) Core(TM) i5-14400F} -> {@code Intel Core i5-14400F}. */
  static String cleanCpuName(String name) {
    return name.replaceAll("\\((R|TM|tm|r)\\)", "").replaceAll("\\s+", " ").trim();
  }

  private static void item(StringBuilder sb, @Nullable String label, String value) {
    sb.append("&nbsp;&nbsp;·&nbsp;&nbsp;");
    if (label != null) sb.append(muted(label)).append("&nbsp;");
    sb.append(nbsp(value));
  }

  private static String muted(String text) {
    return "<font color='" + ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground()) + "'>" + nbsp(text) + "</font>";
  }

  private static String nbsp(String text) {
    return escape(text).replace(" ", "&nbsp;");
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

  /** {@code 9.8 GB}: one decimal, short enough for the small charts. */
  static String gb(double bytes) {
    String[] units = {"KB", "MB", "GB", "TB"};
    double value = bytes / 1024;
    int unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }

  static String mib(double mib) {
    return gb(mib * 1024 * 1024);
  }

  /** GPU | PID | process | VRAM, from nvidia-smi's compute apps. */
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
      return 4;
    }

    @Override
    public String getColumnName(int column) {
      return switch (column) {
        case 0 -> "GPU";
        case 1 -> "PID";
        case 2 -> "Process";
        default -> "VRAM";
      };
    }

    @Override
    public Object getValueAt(int row, int column) {
      SystemSampler.GpuProcess p = rows.get(row);
      return switch (column) {
        case 0 -> p.gpu() < 0 ? "—" : String.valueOf(p.gpu());
        case 1 -> p.pid();
        case 2 -> p.name();
        default -> Double.isNaN(p.vramMiB()) ? "—" : mib(p.vramMiB());
      };
    }
  }
}
