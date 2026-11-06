package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.ui.content.Content;
import com.intellij.ui.table.JBTable;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.WrapLayout;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleFunction;

/**
 * The System Monitor tool window, about this computer, the connected remote one, or a made-up one (Testing);
 * what it shows is set in Settings | Tools | Cobra torch ({@link CobraTorchSettings}). From the top:
 * <ul>
 *   <li>the computer, and while connected its price and the cost so far;</li>
 *   <li>each CPU package: name | power | temperature, then each of its cores, so many a line;</li>
 *   <li>the GPUs: name | power | temperature of them all, then a card for each GPU, so many a line:
 *       name | power | temperature, its VRAM, its load;</li>
 *   <li>RAM; each disk with the space used on it; the processes holding VRAM.</li>
 * </ul>
 * Each measure is a graph of the last minute (three quarters of its place) and a green bar of now (the last
 * quarter), or either one alone. The items of a list are all as wide, and the last line's start at the left.
 * Reads once a second, only while the tool window is visible.
 */
final class MonitorPanel extends JPanel implements Disposable {
  private static final Logger LOG = Logger.getInstance(MonitorPanel.class);
  private static final int DISKS_PER_LINE = 4;
  private static final double GRAPH_WEIGHT = 3, BAR_WEIGHT = 1;

  private final Project project;
  private final Content content;
  private final JBLabel stats = new JBLabel();
  private final JPanel body = new Body();
  private final ProcessModel processModel = new ProcessModel();
  private final JBTable processTable = new JBTable(processModel);

  private @Nullable ScheduledFuture<?> ticker;
  private @Nullable SystemSampler sampler;
  private @Nullable SystemSampler local;
  private @Nullable String unavailable;   // why this computer's CPU and RAM cannot be read, if so

  // the parts of the current layout, rebuilt when the computer, its cores, GPUs or disks, or the settings change
  /** One CPU package's line: name | power | temperature, each null when the settings hide it. */
  private record CpuView(int pkg, @Nullable JBLabel name, @Nullable JBLabel power, @Nullable JBLabel temp) {}

  /** One GPU's card. */
  private record GpuView(@Nullable JBLabel name, @Nullable JBLabel power, @Nullable JBLabel temp, List<Meter> vram, List<Meter> load) {}

  /** One disk's card. */
  private record DiskView(@Nullable JBLabel name, List<Meter> used) {}

  private final List<CpuView> cpuViews = new ArrayList<>();
  private final Map<Integer, List<Meter>> coreMeters = new HashMap<>();   // by the core's place in the sample
  private final List<GpuView> gpuViews = new ArrayList<>();
  private final List<DiskView> diskViews = new ArrayList<>();
  private @Nullable JBLabel gpuName, gpuPower, gpuTemp;
  private @Nullable AreaChart ram;
  private String layoutKey = "";

  /** One reading and what goes with it: where it is from, the price while connected, the GPUs' names and driver. */
  private record Reading(String where, @Nullable String price, boolean testing, SystemSampler.Sample sample,
                         @Nullable GpuInfo.Gpu gpuInfo) {}

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

  /** Background thread: one reading of the computer the toolbar is about, or of the made-up one. */
  private void tick() {
    try {
      boolean testing = CobraTorchSettings.get().testing;
      RemoteHost.Remote remote = testing ? null : RemoteHost.connected(project);
      SystemSampler s = sampler;
      boolean switched = testing ? !(s instanceof SystemSampler.Testing)
          : remote != null ? !(s instanceof SystemSampler.Remote r && r.remote() == remote)
          : !(s instanceof SystemSampler.Local);
      if (switched) s = sampler = testing ? new SystemSampler.Testing() : remote != null ? new SystemSampler.Remote(remote) : local();
      SystemSampler.Sample sample = s == null ? jmxSample() : s.sample();
      Reading reading = s instanceof SystemSampler.Testing t ? new Reading(t.where(), null, true, sample, SystemSampler.Testing.GPU_INFO)
          : s instanceof SystemSampler.Remote r ? new Reading(r.where(), r.remote().price(), false, sample, r.remote().gpu())
          : new Reading(s == null ? "This Computer" : s.where(), null, false, sample, GpuInfo.get());
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

  /** Without OSHI: one CPU chart for all cores, RAM from the JVM, the home folder's disk; the GPUs still from nvidia-smi. */
  private static SystemSampler.Sample jmxSample() {
    com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    double load = os.getCpuLoad();
    List<SystemSampler.Gpu> gpus = SystemSampler.parseGpus(GpuInfo.nvidiaSmi(SystemSampler.GPU_QUERY));
    List<SystemSampler.GpuProcess> processes = gpus.isEmpty() ? List.of()
        : SystemSampler.parseProcesses(GpuInfo.nvidiaSmi(SystemSampler.APPS_QUERY), gpus);
    long[] home = SystemSampler.homeDisk();
    SystemSampler.Cpu cpu = new SystemSampler.Cpu(0, SystemSampler.blankToNull(System.getenv("PROCESSOR_IDENTIFIER")), null,
        Double.NaN, Double.NaN, "unknown", "The CPU's power is not known without OSHI", null);
    return new SystemSampler.Sample(System.currentTimeMillis(), List.of(cpu), load < 0 ? null : new double[] {load},
        new int[] {0}, new int[] {0}, os.getTotalMemorySize() - os.getFreeMemorySize(), os.getTotalMemorySize(), gpus, processes,
        SystemSampler.withHome(List.of(), home[0], home[1]));
  }

  // ---- EDT -----------------------------------------------------------------------------------------------

  private void apply(Reading r) {
    SystemSampler.Sample s = r.sample();
    CobraTorchSettings.Values set = CobraTorchSettings.get();
    List<SystemSampler.Gpu> gpus = s.gpus();
    String key = r.where() + "|" + s.cpus().stream().map(SystemSampler.Cpu::pkg).toList() + " " + s.corePackage().length
        + "|" + gpus.stream().map(g -> g.index() + " " + g.model()).toList()
        + "|" + s.disks().stream().map(d -> d.name() + (d.used() >= 0)).toList() + "|" + set.key();
    if (!key.equals(layoutKey)) rebuild(r, set, key);

    long t = s.time();
    stats.setText(statsHtml(r));
    for (CpuView v : cpuViews) {
      SystemSampler.Cpu cpu = s.cpus().stream().filter(c -> c.pkg() == v.pkg()).findFirst().orElse(null);
      if (cpu != null) updateCpu(v, cpu, s.cpus().size());
    }
    double[] cores = s.cores();
    if (cores != null && cores.length == s.corePackage().length) {
      for (Map.Entry<Integer, List<Meter>> e : coreMeters.entrySet()) {
        double v = cores[e.getKey()] * 100;
        for (Meter m : e.getValue()) m.add(t, v, 100, pct(v));
      }
    }
    updateGpuSummary(r);
    for (int i = 0; i < gpus.size() && i < gpuViews.size(); i++) {
      SystemSampler.Gpu g = gpus.get(i);
      GpuView v = gpuViews.get(i);
      double used = nz(g.memUsed()), total = nz(g.memTotal()), load = nz(g.load());
      for (Meter m : v.vram()) m.add(t, used, total, mib(used) + " of " + mib(total));
      for (Meter m : v.load()) m.add(t, load, 100, pct(load));
      if (v.power() != null) v.power().setText(watts(g.power(), g.powerLimit()));
      if (v.temp() != null) v.temp().setText(celsius(g.temp()));
    }
    if (ram != null && s.ramTotal() > 0) ram.add(t, s.ramUsed(), s.ramTotal(), gb(s.ramUsed()) + " of " + gb(s.ramTotal()));
    for (int i = 0; i < s.disks().size() && i < diskViews.size(); i++) {
      SystemSampler.Disk d = s.disks().get(i);
      for (Meter m : diskViews.get(i).used()) m.add(t, d.used(), d.size(), bytes(d.used()) + " of " + bytes(d.size()));
    }
    processModel.set(s.processes());
  }

  /** The parts the settings ask for, in the order of the class comment. */
  private void rebuild(Reading r, CobraTorchSettings.Values set, String key) {
    layoutKey = key;
    content.setDisplayName(r.testing() ? "Testing" : r.where());
    body.removeAll();
    cpuViews.clear();
    coreMeters.clear();
    gpuViews.clear();
    diskViews.clear();
    gpuName = gpuPower = gpuTemp = null;
    SystemSampler.Sample s = r.sample();

    if (set.showCpu) cpuBlock(s, set);
    if (set.showGpu) gpuBlock(s.gpus(), set);

    body.add(section("RAM"));
    ram = new AreaChart("RAM", AreaChart.ORANGE, MonitorPanel::gb, false);
    body.add(row(ram));

    if (set.showStorage && !s.disks().isEmpty()) storageBlock(s.disks(), set);

    if (set.showGpu && set.gpuProcesses && !s.gpus().isEmpty()) {
      body.add(section("Processes using the GPU"));
      JPanel table = new JPanel(new BorderLayout());
      table.add(processTable.getTableHeader(), BorderLayout.NORTH);
      table.add(processTable, BorderLayout.CENTER);
      body.add(row(table));
    }
    body.revalidate();
    body.repaint();
  }

  /** Each CPU package: its line, then its cores, so many a line. */
  private void cpuBlock(SystemSampler.Sample s, CobraTorchSettings.Values set) {
    int packages = s.cpus().size(), coreCount = s.corePackage().length;
    String title = coreCount == 1 && unavailable != null ? "CPU, all cores"
        : (packages > 1 ? packages + " CPUs" : "CPU") + (coreCount > 0 ? ", " + coreCount + " cores" : "");
    body.add(section(title));
    for (SystemSampler.Cpu cpu : s.cpus()) {
      JBLabel name = set.cpuName ? text() : null, power = set.cpuPower ? text() : null, temp = set.cpuTemperature ? text() : null;
      if (name != null || power != null || temp != null) body.add(row(facts(name, power, temp)));
      CpuView view = new CpuView(cpu.pkg(), name, power, temp);
      cpuViews.add(view);
      updateCpu(view, cpu, packages);

      List<Integer> mine = new ArrayList<>();
      for (int i = 0; i < coreCount; i++) if (s.corePackage()[i] == cpu.pkg()) mine.add(i);
      if (!set.cpuCores || mine.isEmpty() || !(set.coresGraph || set.coresBar)) continue;
      int cols = Math.min(mine.size(), set.perLine());
      JPanel grid = new JPanel(new GridLayout(0, cols, JBUI.scale(6), JBUI.scale(4)));
      for (int i : mine) {
        int number = s.coreNumber()[i];
        List<Meter> meters = new ArrayList<>();
        // "CPU 12  64%", or "12  64%" where that does not fit
        grid.add(pair(coreCount == 1 ? "CPU" : "CPU " + number, AreaChart.BLUE, MonitorPanel::pct, true, set.coresGraph, set.coresBar, meters));
        if (coreCount > 1) for (Meter m : meters) m.setShortName(String.valueOf(number));
        coreMeters.put(i, meters);
      }
      body.add(row(grid));
    }
  }

  /** The GPUs' line: name | power | temperature of them all; then a card for each GPU, so many a line. */
  private void gpuBlock(List<SystemSampler.Gpu> gpus, CobraTorchSettings.Values set) {
    int n = gpus.size();
    body.add(section(n > 1 ? n + " GPUs" : "GPU"));
    if (n == 0) {
      JBLabel none = text();
      none.setText("No NVIDIA GPU");
      none.setForeground(UIUtil.getContextHelpForeground());
      body.add(row(none));
      return;
    }
    gpuName = set.gpuName ? text() : null;
    gpuPower = set.gpuPower ? text() : null;
    gpuTemp = set.gpuTemperature ? text() : null;
    if (gpuName != null) gpuName.setIcon(CondaIcons.NVIDIA);
    if (gpuName != null || gpuPower != null || gpuTemp != null) body.add(row(facts(gpuName, gpuPower, gpuTemp)));

    int cols = Math.min(n, set.gpuPerLine());
    boolean compact = n > 4 || cols > 4;
    boolean mixed = gpus.stream().map(SystemSampler.Gpu::model).distinct().count() > 1;
    JPanel grid = new JPanel(new GridLayout(0, cols, JBUI.scale(compact ? 8 : 12), JBUI.scale(8)));
    grid.setBorder(JBUI.Borders.emptyTop(4));
    for (SystemSampler.Gpu g : gpus) {
      JPanel card = new JPanel(new VerticalLayout(JBUI.scale(2)));
      boolean own = n > 1;   // with one GPU, the line above already says all of it
      JBLabel name = own && set.gpuName ? cell(mixed ? "GPU " + g.index() + " " + g.model() : "GPU " + g.index(),
          "GPU " + g.index() + ": NVIDIA " + g.model()) : null;
      JBLabel power = own && set.gpuPower ? cell("", "Power used, of the GPU's limit") : null;
      JBLabel temp = own && set.gpuTemperature ? cell("", "Temperature") : null;
      if (name != null) name.setFont(JBFont.label().asBold());
      if (name != null || power != null || temp != null) {
        JPanel info = new JPanel(new WeightRow(JBUI.scale(6)));
        info.setBorder(JBUI.Borders.empty(0, 4));
        for (JBLabel l : new JBLabel[] {name, power, temp}) if (l != null) info.add(l, 1.0);
        card.add(info);
      }
      List<Meter> vram = new ArrayList<>(), load = new ArrayList<>();
      if (set.gpuVram && (set.vramGraph || set.vramBar)) {
        card.add(pair("VRAM", AreaChart.AQUA, MonitorPanel::mib, compact, set.vramGraph, set.vramBar, vram));
      }
      if (set.gpuLoad && (set.loadGraph || set.loadBar)) {
        card.add(pair("Load", AreaChart.YELLOW, MonitorPanel::pct, compact, set.loadGraph, set.loadBar, load));
      }
      gpuViews.add(new GpuView(name, power, temp, vram, load));
      grid.add(card);
    }
    body.add(row(grid));
  }

  /** A card for each disk: its maker's logo and name, then the space used on it. */
  private void storageBlock(List<SystemSampler.Disk> disks, CobraTorchSettings.Values set) {
    int n = disks.size(), cols = Math.min(n, DISKS_PER_LINE);
    body.add(section(n > 1 ? "Storage, " + n + " disks" : "Storage"));
    JPanel grid = new JPanel(new GridLayout(0, cols, JBUI.scale(12), JBUI.scale(8)));
    for (SystemSampler.Disk d : disks) {
      JPanel card = new JPanel(new VerticalLayout(JBUI.scale(2)));
      JBLabel name = null;
      if (set.storageName || d.used() < 0) {
        String text = (set.storageName ? d.name() + "  " : "") + bytes(d.size()) + (d.used() < 0 ? ", not mounted here" : "");
        name = cell(text, d.physical() ? d.name() + ", " + bytes(d.size()) : "The file system of the home folder");
        name.setBorder(JBUI.Borders.empty(0, 4));
        if (set.storageName && d.physical()) name.setIcon(CondaIcons.disk(d.name()));
        name.setIconTextGap(JBUI.scale(6));
        card.add(name);
      }
      List<Meter> used = new ArrayList<>();
      if (d.used() >= 0 && (set.storageGraph || set.storageBar)) {
        card.add(pair("Used", AreaChart.MAGENTA, v -> bytes((long) v), true, set.storageGraph, set.storageBar, used));
      }
      diskViews.add(new DiskView(name, used));
      grid.add(card);
    }
    body.add(row(grid));
  }

  /**
   * One measure as the settings ask: a graph of the last minute (three quarters) and a bar of now (a quarter),
   * or either one taking all the room; next to the graph, the bar has no title of its own.
   */
  private static JComponent pair(String name, Color color, DoubleFunction<String> format, boolean compact, boolean graph,
                                 boolean bar, List<Meter> meters) {
    JPanel p = new JPanel(new WeightRow(JBUI.scale(compact ? 3 : 4)));
    if (graph) {
      AreaChart chart = new AreaChart(name, color, format, compact);
      meters.add(chart);
      p.add(chart, GRAPH_WEIGHT);
    }
    if (bar) {
      BarMeter meter = new BarMeter(name, format, compact, !graph);
      meters.add(meter);
      p.add(meter, BAR_WEIGHT);
    }
    return p;
  }

  /** name | power | temperature, with a vertical line between those shown; on two lines if one is too narrow. */
  private static JComponent facts(@Nullable JComponent... items) {
    JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 0));
    for (JComponent c : items) {
      if (c == null) continue;
      if (p.getComponentCount() > 0) p.add(new Separator());
      p.add(c);
    }
    return p;
  }

  private static void updateCpu(CpuView v, SystemSampler.Cpu cpu, int packages) {
    if (v.name() != null) {
      String name = cpu.name() == null ? "CPU" : cleanCpuName(cpu.name());
      v.name().setText("<html>" + (packages > 1 ? muted("CPU " + cpu.pkg()) + "&nbsp;&nbsp;" : "") + "<b>" + nbsp(name) + "</b></html>");
      v.name().setIcon(CondaIcons.cpu(cpu.vendor(), cpu.name()));
    }
    if (v.power() != null) {
      String why = Double.isNaN(cpu.watts()) && cpu.powerWhy() != null ? "&nbsp;" + muted("(" + cpu.powerWhy() + ")") : "";
      v.power().setText("<html>" + muted("power") + "&nbsp;" + nbsp(watts(cpu.watts(), cpu.limitWatts())) + why + "</html>");
      v.power().setToolTipText(Double.isNaN(cpu.watts()) ? cpu.powerNote() : null);
    }
    if (v.temp() != null) {
      v.temp().setText("<html>" + muted("temperature") + "&nbsp;" + nbsp(cpu.temp() == null ? "—" : celsius(cpu.temp())) + "</html>");
    }
  }

  /** "[nvidia] 8 × H200 · VRAM 1120 GB · CUDA 12.8 · driver 570.124.06 | power, all 4100 of 5600 W | temperature 41–62 °C". */
  private void updateGpuSummary(Reading r) {
    List<SystemSampler.Gpu> gpus = r.sample().gpus();
    if (gpuName != null) gpuName.setText(gpuNameHtml(r));
    if (gpuPower != null) {
      double watts = 0, limit = 0;
      for (SystemSampler.Gpu g : gpus) {
        watts += g.power();   // NaN if any is unknown: then the total is too
        limit += g.powerLimit();
      }
      gpuPower.setText("<html>" + muted(gpus.size() > 1 ? "power, all" : "power") + "&nbsp;" + nbsp(watts(watts, limit)) + "</html>");
    }
    if (gpuTemp != null) {
      double min = gpus.stream().mapToDouble(SystemSampler.Gpu::temp).filter(t -> !Double.isNaN(t)).min().orElse(Double.NaN);
      double max = gpus.stream().mapToDouble(SystemSampler.Gpu::temp).filter(t -> !Double.isNaN(t)).max().orElse(Double.NaN);
      String text = Double.isNaN(max) ? "—" : Math.round(min) == Math.round(max) ? celsius(max)
          : String.format(Locale.ROOT, "%.0f–%.0f °C", min, max);
      gpuTemp.setText("<html>" + muted("temperature") + "&nbsp;" + nbsp(text) + "</html>");
    }
  }

  private static JComponent section(String title) {
    JBLabel label = new JBLabel(title);
    label.setFont(JBFont.small());
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setBorder(JBUI.Borders.empty(8, 4, 2, 4));
    return row(label);
  }

  /** A line of text with its facts, like "power 54 of 65 W". */
  private static JBLabel text() {
    JBLabel label = new JBLabel();
    label.setBorder(JBUI.Borders.empty(2, 4));
    label.setIconTextGap(JBUI.scale(6));
    return label;
  }

  /** Plain text in a place of fixed width: cut with "…" when it does not fit, whole in the tooltip. */
  private static JBLabel cell(String text, String tooltip) {
    JBLabel label = new JBLabel(text);
    label.setToolTipText(tooltip);
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

  /** The thin vertical line between name, power and temperature. */
  private static final class Separator extends JComponent {
    private static final JBColor COLOR = JBColor.namedColor("Separator.separatorColor", new JBColor(0xD3D5DB, 0x43454A));

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(JBUI.scale(17), JBUI.scale(20));
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(COLOR);
      g.fillRect(getWidth() / 2, JBUI.scale(4), Math.max(1, JBUI.scale(1)), getHeight() - JBUI.scale(8));
    }
  }

  /** As wide as the tool window, never wider: the lines share out its width instead of running off its edge. */
  private static final class Body extends JPanel implements Scrollable {
    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
      return JBUI.scale(16);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return Math.max(visible.height - JBUI.scale(16), JBUI.scale(16));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  // ---- the text lines --------------------------------------------------------------------------------------

  /** "This Computer", "root@ssh5.vast.ai:41234 · $2.50 per hour, $1.04 so far", or the made-up computer's. */
  private static String statsHtml(Reading r) {
    StringBuilder sb = new StringBuilder("<html><b>").append(escape(r.where())).append("</b>");
    if (r.price() != null) item(sb, "Price", r.price());
    if (r.testing()) sb.append("&nbsp;&nbsp;·&nbsp;&nbsp;").append(muted("turn it off in Settings | Tools | Cobra torch"));
    return sb.append("</html>").toString();
  }

  /** "8 × H100 80GB HBM3 · VRAM 640 GB · CUDA 12.8 · driver 570.124.06". */
  private static String gpuNameHtml(Reading r) {
    GpuInfo.Gpu info = r.gpuInfo();
    StringBuilder sb = new StringBuilder("<html><b>");
    if (info == null) {
      List<SystemSampler.Gpu> gpus = r.sample().gpus();
      return sb.append(nbsp(GpuInfo.groupedName(gpus.stream().map(SystemSampler.Gpu::model).toList()))).append("</b></html>").toString();
    }
    sb.append(nbsp(info.name())).append("</b>");
    item(sb, null, info.vram());
    if (info.cudaVersion() != null) item(sb, null, info.cudaVersion());
    item(sb, "driver", info.driver());
    return sb.append("</html>").toString();
  }

  /** "54 of 65 W", "54 W", "— of 65 W", "—". */
  private static String watts(double watts, double limit) {
    String w = Double.isNaN(watts) ? "—" : String.format(Locale.ROOT, "%.0f", watts);
    return Double.isNaN(limit) || limit <= 0 ? (Double.isNaN(watts) ? "—" : w + " W") : w + " of " + String.format(Locale.ROOT, "%.0f W", limit);
  }

  private static String celsius(double c) {
    return Double.isNaN(c) ? "—" : String.format(Locale.ROOT, "%.0f °C", c);
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

  /** {@code 9.8 GB}: one decimal, short enough for the small charts; in 1024s, as memory is counted. */
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

  /** {@code 1.2 TB}: a disk's size in 1000s, as its maker counts it, so that a 2TB disk is 2.0 TB. */
  static String bytes(long bytes) {
    String[] units = {"KB", "MB", "GB", "TB", "PB"};
    double value = bytes / 1000.0;
    int unit = 0;
    while (value >= 1000 && unit < units.length - 1) {
      value /= 1000;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
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
