package com.prituladima.plugin.intellij;

import org.jetbrains.annotations.Nullable;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One reading per second for the system monitor: every CPU core, RAM, the CPU temperature, every NVIDIA
 * GPU (load, VRAM, temperature, power), the processes holding VRAM, and the free space of the home disk.
 * This computer is read through OSHI and nvidia-smi; a remote computer (Linux) through one small shell
 * script over the existing SSH connection.
 */
abstract class SystemSampler {

  /** {@code cores}: load 0..1 per logical core, null on the first reading (load needs two). */
  record Sample(long time, double @Nullable [] cores, long ramUsed, long ramTotal, @Nullable Double cpuTemp,
                List<Gpu> gpus, List<GpuProcess> processes, long diskFree, long diskTotal) {}

  /** {@code load} in %, memory in MiB, temperature in °C, power in W; NaN where nvidia-smi says [N/A]. */
  record Gpu(int index, double load, double memUsed, double memTotal, double temp, double power, double powerLimit) {}

  /** {@code vramMiB} is NaN where the driver does not tell (Windows, in WDDM mode). */
  record GpuProcess(String pid, String name, double vramMiB) {}

  static final String[] GPU_QUERY = {
      "--query-gpu=index,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit",
      "--format=csv,noheader,nounits"};
  static final String[] APPS_QUERY = {"--query-compute-apps=pid,process_name,used_memory", "--format=csv,noheader,nounits"};

  /** Blocking; called once a second on a background thread, never twice at once. */
  abstract Sample sample() throws Exception;

  /** Where the readings come from, for the monitor's title. */
  abstract String where();

  static List<Gpu> parseGpus(List<String> rows) {
    List<Gpu> gpus = new ArrayList<>();
    for (String row : rows) {
      String[] f = row.split(",\\s*");
      if (f.length < 7) continue;
      gpus.add(new Gpu((int) num(f[0]), num(f[1]), num(f[2]), num(f[3]), num(f[4]), num(f[5]), num(f[6])));
    }
    return gpus;
  }

  /** {@code pid, name, used_memory}; a name may itself contain commas. */
  static List<GpuProcess> parseProcesses(List<String> rows) {
    List<GpuProcess> processes = new ArrayList<>();
    for (String row : rows) {
      int first = row.indexOf(','), last = row.lastIndexOf(',');
      if (first < 0 || last <= first) continue;
      processes.add(new GpuProcess(row.substring(0, first).trim(), row.substring(first + 1, last).trim(), num(row.substring(last + 1))));
    }
    return processes;
  }

  private static double num(String s) {
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      return Double.NaN;
    }
  }

  // ---- this computer -----------------------------------------------------------------------------------

  static final class Local extends SystemSampler {
    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final HardwareAbstractionLayer hal;
    private long[][] ticks;
    private int tick;
    private List<GpuProcess> processes = List.of();

    Local() {
      hal = new SystemInfo().getHardware();
      cpu = hal.getProcessor();
      memory = hal.getMemory();
      ticks = cpu.getProcessorCpuLoadTicks();
    }

    @Override
    String where() {
      return "This Computer";
    }

    @Override
    Sample sample() {
      double[] cores = tick == 0 ? null : cpu.getProcessorCpuLoadBetweenTicks(ticks);
      ticks = cpu.getProcessorCpuLoadTicks();
      double t = hal.getSensors().getCpuTemperature();
      List<Gpu> gpus = parseGpus(GpuInfo.nvidiaSmi(GPU_QUERY));
      if (!gpus.isEmpty() && tick % 2 == 0) processes = parseProcesses(GpuInfo.nvidiaSmi(APPS_QUERY));   // every other second
      long free = 0, total = 0;
      try {
        FileStore store = Files.getFileStore(Path.of(System.getProperty("user.home")));
        free = store.getUsableSpace();
        total = store.getTotalSpace();
      } catch (IOException ignored) {
        // no disk numbers
      }
      tick++;
      return new Sample(System.currentTimeMillis(), cores, memory.getTotal() - memory.getAvailable(), memory.getTotal(),
          t > 0 && !Double.isNaN(t) ? t : null, gpus, gpus.isEmpty() ? List.of() : processes, free, total);
    }
  }

  // ---- the connected remote computer ------------------------------------------------------------------

  static final class Remote extends SystemSampler {
    /** Sections marked @@name; the CPU temperature from the first known hwmon sensor, in m°C. */
    private static final String SCRIPT = """
        echo @@cpu; grep '^cpu[0-9]' /proc/stat
        echo @@mem; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
        echo @@temp
        for d in /sys/class/hwmon/hwmon*; do
          case "$(cat "$d/name" 2>/dev/null)" in
            coretemp|k10temp|zenpower|cpu_thermal|soc_thermal) cat "$d/temp1_input" 2>/dev/null; break;;
          esac
        done
        echo @@gpu; nvidia-smi %s 2>/dev/null
        echo @@apps; nvidia-smi %s 2>/dev/null
        echo @@disk; df -Pk "$HOME" 2>/dev/null | tail -n 1
        """.formatted(String.join(" ", GPU_QUERY), String.join(" ", APPS_QUERY));

    private final RemoteHost.Remote remote;
    private long[][] previous;

    Remote(RemoteHost.Remote remote) {
      this.remote = remote;
    }

    RemoteHost.Remote remote() {
      return remote;
    }

    @Override
    String where() {
      return remote.label();
    }

    @Override
    Sample sample() throws Exception {
      Map<String, List<String>> s = new LinkedHashMap<>();
      List<String> current = null;
      for (String line : SshConnection.lines(remote.ssh.exec(SCRIPT, null, 5_000).out())) {
        if (line.startsWith("@@")) s.put(line.substring(2), current = new ArrayList<>());
        else if (current != null) current.add(line);
      }
      // cpuN user nice system idle iowait irq softirq steal ...: busy is all but idle and iowait
      List<String> cpuRows = s.getOrDefault("cpu", List.of());
      long[][] now = new long[cpuRows.size()][];
      for (int i = 0; i < cpuRows.size(); i++) {
        String[] f = cpuRows.get(i).split("\\s+");
        long total = 0, idle = 0;
        for (int k = 1; k < f.length && k <= 8; k++) {
          long v = Long.parseLong(f[k]);
          total += v;
          if (k == 4 || k == 5) idle += v;
        }
        now[i] = new long[] {total, idle};
      }
      double[] cores = null;
      if (previous != null && previous.length == now.length) {
        cores = new double[now.length];
        for (int i = 0; i < now.length; i++) {
          long dt = now[i][0] - previous[i][0], di = now[i][1] - previous[i][1];
          cores[i] = dt <= 0 ? 0 : Math.max(0, Math.min(1, 1 - (double) di / dt));
        }
      }
      previous = now;
      long memTotal = 0, memAvailable = 0;
      for (String line : s.getOrDefault("mem", List.of())) {
        long kb = Long.parseLong(line.replaceAll("\\D+", ""));
        if (line.startsWith("MemTotal")) memTotal = kb * 1024;
        else memAvailable = kb * 1024;
      }
      Double temp = null;
      List<String> t = s.getOrDefault("temp", List.of());
      if (!t.isEmpty()) {
        try {
          temp = Long.parseLong(t.get(0).trim()) / 1000.0;
        } catch (NumberFormatException ignored) {
          // no sensor
        }
      }
      long diskFree = 0, diskTotal = 0;
      List<String> d = s.getOrDefault("disk", List.of());
      if (!d.isEmpty()) {
        String[] f = d.get(0).split("\\s+");   // filesystem 1024-blocks used available capacity mount
        if (f.length >= 4) {
          try {
            diskTotal = Long.parseLong(f[1]) * 1024;
            diskFree = Long.parseLong(f[3]) * 1024;
          } catch (NumberFormatException ignored) {
            // unusual df
          }
        }
      }
      return new Sample(System.currentTimeMillis(), cores, memTotal - memAvailable, memTotal, temp,
          parseGpus(s.getOrDefault("gpu", List.of())), parseProcesses(s.getOrDefault("apps", List.of())), diskFree, diskTotal);
    }
  }
}
