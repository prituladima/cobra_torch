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
 * of the IDE's settings. Everything is on by default, each measure as a graph and a bar, 16 cores and 4 GPUs
 * a line; Testing, which shows a made-up computer, is off.
 */
@Service(Service.Level.APP)
@State(name = "CobraTorchSettings", storages = @Storage("cobra-torch.xml"))
public final class CobraTorchSettings implements PersistentStateComponent<CobraTorchSettings.Values> {
  static final int MIN_PER_LINE = 1, MAX_CORES_PER_LINE = 64, MAX_GPUS_PER_LINE = 16;

  /** The settings; public fields, as the IDE stores them. */
  public static final class Values {
    public boolean showCpu = true;
    public boolean cpuName = true;
    public boolean cpuPower = true;
    public boolean cpuTemperature = true;
    public boolean cpuCores = true;
    public int coresPerLine = 16;
    public boolean coresGraph = true;
    public boolean coresBar = true;

    public boolean showGpu = true;
    public boolean gpuName = true;
    public boolean gpuPower = true;
    public boolean gpuTemperature = true;
    public int gpusPerLine = 4;
    public boolean gpuVram = true;
    public boolean vramGraph = true;
    public boolean vramBar = true;
    public boolean gpuLoad = true;
    public boolean loadGraph = true;
    public boolean loadBar = true;
    public boolean gpuProcesses = true;

    public boolean showStorage = true;
    public boolean storageName = true;
    public boolean storageGraph = true;
    public boolean storageBar = true;

    public boolean testing = false;

    Values copy() {
      Values v = new Values();
      XmlSerializerUtil.copyBean(this, v);
      return v;
    }

    int perLine() {
      return Math.max(MIN_PER_LINE, Math.min(MAX_CORES_PER_LINE, coresPerLine));
    }

    int gpuPerLine() {
      return Math.max(MIN_PER_LINE, Math.min(MAX_GPUS_PER_LINE, gpusPerLine));
    }

    /** All the values in one string: the System Monitor rebuilds when it changes. */
    String key() {
      return "" + showCpu + cpuName + cpuPower + cpuTemperature + cpuCores + perLine() + coresGraph + coresBar
          + showGpu + gpuName + gpuPower + gpuTemperature + gpuPerLine() + gpuVram + vramGraph + vramBar + gpuLoad + loadGraph
          + loadBar + gpuProcesses + showStorage + storageName + storageGraph + storageBar + testing;
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
