package com.prituladima.plugin.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Settings | Tools | Cobra torch: what the System Monitor shows. A setting under an unchecked one is greyed out;
 * each measure can show a graph of the last minute, a bar of now, or both side by side.
 */
public final class CobraTorchConfigurable implements Configurable {
  private final JBCheckBox showCpu = new JBCheckBox("Show CPU");
  private final JBCheckBox cpuName = new JBCheckBox("Name, with the Intel or AMD logo");
  private final JBCheckBox cpuPower = new JBCheckBox("CPU power: x of y W");
  private final JBCheckBox cpuTemperature = new JBCheckBox("CPU temperature");
  private final JBCheckBox cpuCores = new JBCheckBox("Load of each core");
  private final JBIntSpinner coresPerLine = new JBIntSpinner(16, CobraTorchSettings.MIN_PER_LINE, CobraTorchSettings.MAX_CORES_PER_LINE);
  private final JBCheckBox coresGraph = new JBCheckBox("Graph: load in % over the last minute");
  private final JBCheckBox coresBar = new JBCheckBox("Bar: current load in %");

  private final JBCheckBox showGpu = new JBCheckBox("Show GPU info");
  private final JBCheckBox gpuName = new JBCheckBox("Name, with the NVIDIA logo: 2 × model, VRAM, CUDA, driver");
  private final JBCheckBox gpuPower = new JBCheckBox("Power of each GPU: x of y W");
  private final JBCheckBox gpuTemperature = new JBCheckBox("Temperature of each GPU");
  private final JBIntSpinner gpusPerLine = new JBIntSpinner(4, CobraTorchSettings.MIN_PER_LINE, CobraTorchSettings.MAX_GPUS_PER_LINE);
  private final JBCheckBox gpuVram = new JBCheckBox("VRAM used");
  private final JBCheckBox vramGraph = new JBCheckBox("Graph: memory used over the last minute");
  private final JBCheckBox vramBar = new JBCheckBox("Bar: memory used now");
  private final JBCheckBox gpuLoad = new JBCheckBox("GPU load");
  private final JBCheckBox loadGraph = new JBCheckBox("Graph: load in % over the last minute");
  private final JBCheckBox loadBar = new JBCheckBox("Bar: current load in %");
  private final JBCheckBox gpuProcesses = new JBCheckBox("List of processes using the GPU");

  private final JBCheckBox showStorage = new JBCheckBox("Show storage info");
  private final JBCheckBox storageName = new JBCheckBox("Name of each disk, with its maker's logo");
  private final JBCheckBox storageGraph = new JBCheckBox("Graph: space used over the last minute");
  private final JBCheckBox storageBar = new JBCheckBox("Bar: space used now");

  private final JBCheckBox testing = new JBCheckBox("Testing: show a made-up computer instead of the real one");

  private @Nullable JPanel panel;

  @Override
  public @Nls String getDisplayName() {
    return "Cobra torch";
  }

  @Override
  public @Nullable JComponent createComponent() {
    JPanel p = new JPanel(new VerticalLayout(JBUI.scale(2)));
    p.add(title("System Monitor"));
    p.add(showCpu);
    p.add(indent(1, cpuName));
    p.add(indent(1, cpuPower));
    p.add(indent(2, hint("On Linux only root may read the CPU's energy counter; Windows and macOS do not offer it.")));
    p.add(indent(1, cpuTemperature));
    p.add(indent(1, cpuCores));
    p.add(indent(2, perLine(coresPerLine, CobraTorchSettings.MAX_CORES_PER_LINE)));
    p.add(indent(2, coresGraph));
    p.add(indent(2, coresBar));

    p.add(gap(showGpu));
    p.add(indent(1, gpuName));
    p.add(indent(1, gpuPower));
    p.add(indent(1, gpuTemperature));
    p.add(indent(1, perLine(gpusPerLine, CobraTorchSettings.MAX_GPUS_PER_LINE)));
    p.add(indent(1, gpuVram));
    p.add(indent(2, vramGraph));
    p.add(indent(2, vramBar));
    p.add(indent(1, gpuLoad));
    p.add(indent(2, loadGraph));
    p.add(indent(2, loadBar));
    p.add(indent(1, gpuProcesses));

    p.add(gap(showStorage));
    p.add(indent(1, storageName));
    p.add(indent(1, storageGraph));
    p.add(indent(1, storageBar));

    p.add(gap(testing));
    p.add(indent(1, hint("2 AMD EPYC CPUs of 32 cores each, 8 NVIDIA H200 GPUs, 2 Samsung SSDs of 2 TB each;"
        + " the System Monitor and the GPU in the toolbar show it.")));

    for (AbstractButton b : List.of(showCpu, cpuCores, showGpu, gpuVram, gpuLoad, showStorage)) b.addItemListener(e -> updateEnabled());
    panel = p;
    reset();
    return p;
  }

  /** What is under an unchecked box is greyed out. */
  private void updateEnabled() {
    boolean cpu = showCpu.isSelected(), cores = cpu && cpuCores.isSelected();
    for (JComponent c : List.of(cpuName, cpuPower, cpuTemperature, cpuCores)) c.setEnabled(cpu);
    for (JComponent c : List.of(coresPerLine, coresGraph, coresBar)) c.setEnabled(cores);
    boolean gpu = showGpu.isSelected();
    for (JComponent c : List.of(gpuName, gpuPower, gpuTemperature, gpusPerLine, gpuVram, gpuLoad, gpuProcesses)) c.setEnabled(gpu);
    vramGraph.setEnabled(gpu && gpuVram.isSelected());
    vramBar.setEnabled(gpu && gpuVram.isSelected());
    loadGraph.setEnabled(gpu && gpuLoad.isSelected());
    loadBar.setEnabled(gpu && gpuLoad.isSelected());
    for (JComponent c : List.of(storageName, storageGraph, storageBar)) c.setEnabled(showStorage.isSelected());
  }

  private CobraTorchSettings.Values read() {
    CobraTorchSettings.Values v = new CobraTorchSettings.Values();
    v.showCpu = showCpu.isSelected();
    v.cpuName = cpuName.isSelected();
    v.cpuPower = cpuPower.isSelected();
    v.cpuTemperature = cpuTemperature.isSelected();
    v.cpuCores = cpuCores.isSelected();
    v.coresPerLine = coresPerLine.getNumber();
    v.coresGraph = coresGraph.isSelected();
    v.coresBar = coresBar.isSelected();
    v.showGpu = showGpu.isSelected();
    v.gpuName = gpuName.isSelected();
    v.gpuPower = gpuPower.isSelected();
    v.gpuTemperature = gpuTemperature.isSelected();
    v.gpusPerLine = gpusPerLine.getNumber();
    v.gpuVram = gpuVram.isSelected();
    v.vramGraph = vramGraph.isSelected();
    v.vramBar = vramBar.isSelected();
    v.gpuLoad = gpuLoad.isSelected();
    v.loadGraph = loadGraph.isSelected();
    v.loadBar = loadBar.isSelected();
    v.gpuProcesses = gpuProcesses.isSelected();
    v.showStorage = showStorage.isSelected();
    v.storageName = storageName.isSelected();
    v.storageGraph = storageGraph.isSelected();
    v.storageBar = storageBar.isSelected();
    v.testing = testing.isSelected();
    return v;
  }

  @Override
  public boolean isModified() {
    return !read().key().equals(CobraTorchSettings.get().key());
  }

  @Override
  public void apply() {
    CobraTorchSettings.getInstance().set(read());
  }

  @Override
  public void reset() {
    CobraTorchSettings.Values v = CobraTorchSettings.get();
    showCpu.setSelected(v.showCpu);
    cpuName.setSelected(v.cpuName);
    cpuPower.setSelected(v.cpuPower);
    cpuTemperature.setSelected(v.cpuTemperature);
    cpuCores.setSelected(v.cpuCores);
    coresPerLine.setNumber(v.perLine());
    coresGraph.setSelected(v.coresGraph);
    coresBar.setSelected(v.coresBar);
    showGpu.setSelected(v.showGpu);
    gpuName.setSelected(v.gpuName);
    gpuPower.setSelected(v.gpuPower);
    gpuTemperature.setSelected(v.gpuTemperature);
    gpusPerLine.setNumber(v.gpuPerLine());
    gpuVram.setSelected(v.gpuVram);
    vramGraph.setSelected(v.vramGraph);
    vramBar.setSelected(v.vramBar);
    gpuLoad.setSelected(v.gpuLoad);
    loadGraph.setSelected(v.loadGraph);
    loadBar.setSelected(v.loadBar);
    gpuProcesses.setSelected(v.gpuProcesses);
    showStorage.setSelected(v.showStorage);
    storageName.setSelected(v.storageName);
    storageGraph.setSelected(v.storageGraph);
    storageBar.setSelected(v.storageBar);
    testing.setSelected(v.testing);
    updateEnabled();
  }

  @Override
  public void disposeUIResources() {
    panel = null;
  }

  /** "Max per line: [16] 1 to 64". */
  private static JComponent perLine(JBIntSpinner spinner, int max) {
    JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    line.add(new JBLabel("Max per line:"));
    line.add(spinner);
    line.add(hint(CobraTorchSettings.MIN_PER_LINE + " to " + max));
    return line;
  }

  private static JComponent indent(int level, JComponent c) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    p.setBorder(JBUI.Borders.emptyLeft(level * 22));
    p.add(c);
    return p;
  }

  private static JComponent gap(JComponent c) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    p.setBorder(JBUI.Borders.emptyTop(10));
    p.add(c);
    return p;
  }

  private static JComponent title(String text) {
    JBLabel label = new JBLabel(text);
    label.setFont(JBFont.label().asBold());
    label.setBorder(JBUI.Borders.emptyBottom(6));
    return label;
  }

  private static JBLabel hint(String text) {
    JBLabel label = new JBLabel(text);
    label.setForeground(UIUtil.getContextHelpForeground());
    label.setFont(JBFont.small());
    return label;
  }
}
