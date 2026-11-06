package com.prituladima.plugin.intellij;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.panels.VerticalLayout;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractButton;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Settings | Tools | Cobra torch: what the System Monitor shows. A setting under an unchecked one is greyed out;
 * graph or bar is one choice of two.
 */
public final class CobraTorchConfigurable implements Configurable {
  private final JBCheckBox showCpu = new JBCheckBox("Show CPU");
  private final JBCheckBox cpuName = new JBCheckBox("Name, with the Intel or AMD logo");
  private final JBCheckBox cpuPower = new JBCheckBox("CPU power: x of y W");
  private final JBCheckBox cpuTemperature = new JBCheckBox("CPU temperature");
  private final JBCheckBox cpuCores = new JBCheckBox("Load of each core");
  private final JBIntSpinner perLine = new JBIntSpinner(16, CobraTorchSettings.MIN_PER_LINE, CobraTorchSettings.MAX_PER_LINE);
  private final JBRadioButton coresGraph = new JBRadioButton("Graph: load in % over the last minute");
  private final JBRadioButton coresBar = new JBRadioButton("Bar: current load in %");

  private final JBCheckBox showGpu = new JBCheckBox("Show GPU info");
  private final JBCheckBox gpuName = new JBCheckBox("Name, with the NVIDIA logo: 2 × model, VRAM, CUDA, driver");
  private final JBCheckBox gpuPower = new JBCheckBox("Power of each GPU: x of y W");
  private final JBCheckBox gpuTemperature = new JBCheckBox("Temperature of each GPU");
  private final JBCheckBox gpuVram = new JBCheckBox("VRAM used");
  private final JBRadioButton vramGraph = new JBRadioButton("Graph: memory used over the last minute");
  private final JBRadioButton vramBar = new JBRadioButton("Bar: memory used now");
  private final JBCheckBox gpuLoad = new JBCheckBox("GPU load");
  private final JBRadioButton loadGraph = new JBRadioButton("Graph: load in % over the last minute");
  private final JBRadioButton loadBar = new JBRadioButton("Bar: current load in %");
  private final JBCheckBox gpuProcesses = new JBCheckBox("List of processes using the GPU");

  private final JBCheckBox showStorage = new JBCheckBox("Show storage: free space of the home folder's disk, as a number");

  private @Nullable JPanel panel;

  @Override
  public @Nls String getDisplayName() {
    return "Cobra torch";
  }

  @Override
  public @Nullable JComponent createComponent() {
    group(coresGraph, coresBar);
    group(vramGraph, vramBar);
    group(loadGraph, loadBar);

    JPanel p = new JPanel(new VerticalLayout(JBUI.scale(2)));
    p.add(title("System Monitor"));
    p.add(showCpu);
    p.add(indent(1, cpuName));
    p.add(indent(1, cpuPower));
    p.add(indent(2, hint("On Linux only root may read the CPU's energy counter; Windows and macOS do not offer it.")));
    p.add(indent(1, cpuTemperature));
    p.add(indent(1, cpuCores));
    JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    line.add(new JBLabel("Max per line:"));
    line.add(perLine);
    line.add(hint(CobraTorchSettings.MIN_PER_LINE + " to " + CobraTorchSettings.MAX_PER_LINE));
    p.add(indent(2, line));
    p.add(indent(2, coresGraph));
    p.add(indent(2, coresBar));

    p.add(gap(showGpu));
    p.add(indent(1, gpuName));
    p.add(indent(1, gpuPower));
    p.add(indent(1, gpuTemperature));
    p.add(indent(1, gpuVram));
    p.add(indent(2, vramGraph));
    p.add(indent(2, vramBar));
    p.add(indent(1, gpuLoad));
    p.add(indent(2, loadGraph));
    p.add(indent(2, loadBar));
    p.add(indent(1, gpuProcesses));

    p.add(gap(showStorage));

    for (AbstractButton b : List.of(showCpu, cpuCores, showGpu, gpuVram, gpuLoad)) b.addItemListener(e -> updateEnabled());
    panel = p;
    reset();
    return p;
  }

  /** What is under an unchecked box is greyed out. */
  private void updateEnabled() {
    boolean cpu = showCpu.isSelected(), cores = cpu && cpuCores.isSelected();
    for (JComponent c : List.of(cpuName, cpuPower, cpuTemperature, cpuCores)) c.setEnabled(cpu);
    for (JComponent c : List.of(perLine, coresGraph, coresBar)) c.setEnabled(cores);
    boolean gpu = showGpu.isSelected();
    for (JComponent c : List.of(gpuName, gpuPower, gpuTemperature, gpuVram, gpuLoad, gpuProcesses)) c.setEnabled(gpu);
    vramGraph.setEnabled(gpu && gpuVram.isSelected());
    vramBar.setEnabled(gpu && gpuVram.isSelected());
    loadGraph.setEnabled(gpu && gpuLoad.isSelected());
    loadBar.setEnabled(gpu && gpuLoad.isSelected());
  }

  private CobraTorchSettings.Values read() {
    CobraTorchSettings.Values v = new CobraTorchSettings.Values();
    v.showCpu = showCpu.isSelected();
    v.cpuName = cpuName.isSelected();
    v.cpuPower = cpuPower.isSelected();
    v.cpuTemperature = cpuTemperature.isSelected();
    v.cpuCores = cpuCores.isSelected();
    v.coresPerLine = perLine.getNumber();
    v.coresAsBars = coresBar.isSelected();
    v.showGpu = showGpu.isSelected();
    v.gpuName = gpuName.isSelected();
    v.gpuPower = gpuPower.isSelected();
    v.gpuTemperature = gpuTemperature.isSelected();
    v.gpuVram = gpuVram.isSelected();
    v.vramAsBars = vramBar.isSelected();
    v.gpuLoad = gpuLoad.isSelected();
    v.loadAsBars = loadBar.isSelected();
    v.gpuProcesses = gpuProcesses.isSelected();
    v.showStorage = showStorage.isSelected();
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
    perLine.setNumber(v.perLine());
    (v.coresAsBars ? coresBar : coresGraph).setSelected(true);
    showGpu.setSelected(v.showGpu);
    gpuName.setSelected(v.gpuName);
    gpuPower.setSelected(v.gpuPower);
    gpuTemperature.setSelected(v.gpuTemperature);
    gpuVram.setSelected(v.gpuVram);
    (v.vramAsBars ? vramBar : vramGraph).setSelected(true);
    gpuLoad.setSelected(v.gpuLoad);
    (v.loadAsBars ? loadBar : loadGraph).setSelected(true);
    gpuProcesses.setSelected(v.gpuProcesses);
    showStorage.setSelected(v.showStorage);
    updateEnabled();
  }

  @Override
  public void disposeUIResources() {
    panel = null;
  }

  private static void group(AbstractButton... buttons) {
    ButtonGroup g = new ButtonGroup();
    for (AbstractButton b : buttons) g.add(b);
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
