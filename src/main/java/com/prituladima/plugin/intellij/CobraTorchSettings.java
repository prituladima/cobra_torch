package com.prituladima.plugin.intellij;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

/**
 * What the System Monitor shows (Settings | Tools | Cobra torch), for every project; kept in cobra-torch.xml
 * of the IDE's settings. Everything is on by default, the cores as graphs, 16 a line.
 */
@Service(Service.Level.APP)
@State(name = "CobraTorchSettings", storages = @Storage("cobra-torch.xml"))
public final class CobraTorchSettings implements PersistentStateComponent<CobraTorchSettings.Values> {
  static final int MIN_PER_LINE = 1, MAX_PER_LINE = 64;

  /** The settings; public fields, as the IDE stores them. */
  public static final class Values {
    public boolean showCpu = true;
    public boolean cpuName = true;
    public boolean cpuPower = true;
    public boolean cpuTemperature = true;
    public boolean cpuCores = true;
    public int coresPerLine = 16;
    public boolean coresAsBars = false;

    public boolean showGpu = true;
    public boolean gpuName = true;
    public boolean gpuPower = true;
    public boolean gpuTemperature = true;
    public boolean gpuVram = true;
    public boolean vramAsBars = false;
    public boolean gpuLoad = true;
    public boolean loadAsBars = false;
    public boolean gpuProcesses = true;

    public boolean showStorage = true;

    Values copy() {
      Values v = new Values();
      XmlSerializerUtil.copyBean(this, v);
      return v;
    }

    int perLine() {
      return Math.max(MIN_PER_LINE, Math.min(MAX_PER_LINE, coresPerLine));
    }

    /** All the values in one string: the System Monitor rebuilds when it changes. */
    String key() {
      return "" + showCpu + cpuName + cpuPower + cpuTemperature + cpuCores + perLine() + coresAsBars
          + showGpu + gpuName + gpuPower + gpuTemperature + gpuVram + vramAsBars + gpuLoad + loadAsBars + gpuProcesses
          + showStorage;
    }
  }

  private Values values = new Values();

  static CobraTorchSettings getInstance() {
    return ApplicationManager.getApplication().getService(CobraTorchSettings.class);
  }

  /** The current settings; read on any thread, never changed in place. */
  static Values get() {
    return getInstance().values;
  }

  void set(Values v) {
    values = v.copy();
  }

  @Override
  public @NotNull Values getState() {
    return values;
  }

  @Override
  public void loadState(@NotNull Values state) {
    values = state.copy();
  }
}
