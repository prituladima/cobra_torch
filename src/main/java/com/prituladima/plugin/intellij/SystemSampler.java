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
 * GPU (load, VRAM, temperature, power, model), the processes holding VRAM and their GPU, and the disk of
 * the home folder.
 * This computer is read through OSHI and nvidia-smi; a remote computer (Linux) through one small shell
 * script over the existing SSH connection.
 */
abstract class SystemSampler {

  /** {@code cores}: load 0..1 per logical core, null on the first reading (load needs two). */
  record Sample(long time, double @Nullable [] cores, Cpu cpu, long ramUsed, long ramTotal, @Nullable Double cpuTemp,
                List<Gpu> gpus, List<GpuProcess> processes, long diskFree, long diskTotal) {}

  /**
   * The CPU: its name ({@code Intel(R) Core(TM) i5-14400F}) and maker ({@code GenuineIntel}, {@code AuthenticAMD}),
   * null if unknown; its power and sustained power limit in W, NaN if unknown, with {@code powerWhy} saying why
   * in two words ("root only") and {@code powerNote} in full.
   */
  record Cpu(@Nullable String name, @Nullable String vendor, double watts, double limitWatts, @Nullable String powerWhy,
             @Nullable String powerNote) {}

  private static final String RAPL = "/sys/class/powercap";
  private static final String ROOT_ONLY = "Linux lets only root read the CPU's energy counter (since the PLATYPUS attack). "
      + "To show the power until the next restart: sudo chmod o+r " + RAPL + "/intel-rapl:0/energy_uj";

  /**
   * The CPU's power from its RAPL energy counters (Intel, and AMD since Linux 5.11), one line per CPU package:
   * {@code energy_uj max_energy_range_uj constraint_0_power_limit_uw}, {@code -} for a counter that cannot be read.
   * Power is the energy used between two readings over the time between them.
   */
  static final class Rapl {
    private long @Nullable [] last;
    private long lastNanos;

    /** {watts, limit watts}, NaN where unknown; the note says why the power is unknown. */
    Cpu read(@Nullable String name, @Nullable String vendor, List<String> lines, long nanos, String where) {
      int n = lines.size();
      long[] energy = new long[n], range = new long[n];
      double limit = 0;
      boolean readable = n > 0;
      for (int i = 0; i < n; i++) {
        String[] f = lines.get(i).trim().split("\\s+");
        if (f.length < 3) {
          readable = false;
          continue;
        }
        energy[i] = longOr(f[0], -1);
        range[i] = longOr(f[1], 0);
        if (energy[i] < 0) readable = false;
        long uw = longOr(f[2], 0);
        if (uw > 0) limit += uw / 1e6;
      }
      double watts = Double.NaN;
      if (readable && last != null && last.length == n && nanos > lastNanos) {
        double joules = 0;
        for (int i = 0; i < n; i++) {
          long d = energy[i] - last[i];
          if (d < 0) d += range[i];   // the counter wrapped
          joules += d / 1e6;
        }
        watts = joules / ((nanos - lastNanos) / 1e9);
      }
      last = readable ? energy : null;
      lastNanos = nanos;
      String why = readable ? null : n == 0 ? "not offered" : "root only";
      String note = readable ? null : n == 0 ? where + " does not tell the CPU's power (no " + RAPL + ")" : ROOT_ONLY;
      return new Cpu(name, vendor, watts, limit > 0 ? limit : Double.NaN, why, note);
    }

    private static long longOr(String s, long fallback) {
      try {
        return Long.parseLong(s.trim());
      } catch (NumberFormatException e) {
        return fallback;
      }
    }
  }

  /** This computer's RAPL lines, as {@link Rapl} reads them; none outside Linux. */
  static List<String> localRapl() {
    List<String> lines = new ArrayList<>();
    try (var dirs = Files.newDirectoryStream(Path.of(RAPL), "intel-rapl:*")) {
      List<Path> packages = new ArrayList<>();
      for (Path d : dirs) if (d.getFileName().toString().indexOf(':') == d.getFileName().toString().lastIndexOf(':')) packages.add(d);
      packages.sort(null);
      for (Path d : packages) lines.add(read(d.resolve("energy_uj"), "-") + " " + read(d.resolve("max_energy_range_uj"), "0")
          + " " + read(d.resolve("constraint_0_power_limit_uw"), "0"));
    } catch (IOException | RuntimeException e) {
      // not Linux, or no RAPL here
    }
    return lines;
  }

  private static String read(Path file, String fallback) {
    try {
      return Files.readString(file).trim();
    } catch (IOException | RuntimeException e) {   // energy_uj: root only
      return fallback;
    }
  }

  /**
   * {@code load} in %, memory in MiB, temperature in °C, power in W; NaN where nvidia-smi says [N/A].
   * {@code model} without the "NVIDIA " in front.
   */
  record Gpu(int index, String uuid, double load, double memUsed, double memTotal, double temp, double power,
             double powerLimit, String model) {}

  /** {@code gpu}: the index of the GPU it runs on, -1 if unknown; {@code vramMiB} is NaN where the driver does not tell (Windows, in WDDM mode). */
  record GpuProcess(int gpu, String pid, String name, double vramMiB) {}

  /** One row per GPU; the name last, so that the fields before it are always in place. */
  static final String[] GPU_QUERY = {
      "--query-gpu=index,uuid,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit,name",
      "--format=csv,noheader,nounits"};
  static final String[] APPS_QUERY = {"--query-compute-apps=gpu_uuid,pid,process_name,used_memory", "--format=csv,noheader,nounits"};

  /** Blocking; called once a second on a background thread, never twice at once. */
  abstract Sample sample() throws Exception;

  /** Where the readings come from, for the monitor's title. */
  abstract String where();

  static List<Gpu> parseGpus(List<String> rows) {
    List<Gpu> gpus = new ArrayList<>();
    for (String row : rows) {
      String[] f = row.split(",\\s*", 9);
      if (f.length < 9 || Double.isNaN(num(f[0]))) continue;
      gpus.add(new Gpu((int) num(f[0]), f[1].trim(), num(f[2]), num(f[3]), num(f[4]), num(f[5]), num(f[6]), num(f[7]),
          GpuInfo.model(f[8])));
    }
    return gpus;
  }

  /** {@code gpu_uuid, pid, name, used_memory}; a name may itself contain commas. */
  static List<GpuProcess> parseProcesses(List<String> rows, List<Gpu> gpus) {
    List<GpuProcess> processes = new ArrayList<>();
    for (String row : rows) {
      int first = row.indexOf(','), second = first < 0 ? -1 : row.indexOf(',', first + 1), last = row.lastIndexOf(',');
      if (second < 0 || last <= second) continue;
      String uuid = row.substring(0, first).trim();
      int gpu = gpus.stream().filter(g -> g.uuid().equals(uuid)).mapToInt(Gpu::index).findFirst().orElse(-1);
      processes.add(new GpuProcess(gpu, row.substring(first + 1, second).trim(), row.substring(second + 1, last).trim(),
          num(row.substring(last + 1))));
    }
    return processes;
  }

  /** The disk of the home folder: {free, total} bytes, zeros if unknown. */
  static long[] homeDisk() {
    try {
      FileStore store = Files.getFileStore(Path.of(System.getProperty("user.home")));
      return new long[] {store.getUsableSpace(), store.getTotalSpace()};
    } catch (IOException e) {
      return new long[] {0, 0};
    }
  }

  private static @Nullable String first(@Nullable List<String> lines) {
    return lines == null || lines.isEmpty() ? null : blankToNull(lines.get(0));
  }

  static @Nullable String blankToNull(@Nullable String s) {
    return s == null || s.isBlank() ? null : s.trim();
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
    private final @Nullable String cpuName, cpuVendor;
    private final Rapl rapl = new Rapl();
    private final boolean linux = System.getProperty("os.name", "").startsWith("Linux");
    private long[][] ticks;
    private int tick;
    private List<GpuProcess> processes = List.of();

    Local() {
      hal = new SystemInfo().getHardware();
      cpu = hal.getProcessor();
      memory = hal.getMemory();
      ticks = cpu.getProcessorCpuLoadTicks();
      CentralProcessor.ProcessorIdentifier id = cpu.getProcessorIdentifier();
      cpuName = blankToNull(id.getName());
      cpuVendor = blankToNull(id.getVendor());
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
      if (!gpus.isEmpty() && tick % 2 == 0) processes = parseProcesses(GpuInfo.nvidiaSmi(APPS_QUERY), gpus);   // every other second
      long[] disk = homeDisk();
      tick++;
      Cpu c = linux ? rapl.read(cpuName, cpuVendor, localRapl(), System.nanoTime(), "This computer")
          : new Cpu(cpuName, cpuVendor, Double.NaN, Double.NaN, "not offered", "Windows and macOS do not let programs read the CPU's power");
      return new Sample(System.currentTimeMillis(), cores, c, memory.getTotal() - memory.getAvailable(), memory.getTotal(),
          t > 0 && !Double.isNaN(t) ? t : null, gpus, gpus.isEmpty() ? List.of() : processes, disk[0], disk[1]);
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
        echo @@cpuname; grep -m1 -E '^(model name|Model)[[:space:]]*:' /proc/cpuinfo | cut -d: -f2-
        echo @@cpuvendor; grep -m1 '^vendor_id' /proc/cpuinfo | cut -d: -f2-
        echo @@rapl
        for d in %s/intel-rapl:*; do
          case "${d##*/}" in *:*:*) continue;; esac
          [ -d "$d" ] || continue
          echo "$(cat "$d/energy_uj" 2>/dev/null || echo -) $(cat "$d/max_energy_range_uj" 2>/dev/null || echo 0) $(cat "$d/constraint_0_power_limit_uw" 2>/dev/null || echo 0)"
        done
        """.formatted(String.join(" ", GPU_QUERY), String.join(" ", APPS_QUERY), RAPL);

    private final RemoteHost.Remote remote;
    private final Rapl rapl = new Rapl();
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
      List<Gpu> gpus = parseGpus(s.getOrDefault("gpu", List.of()));
      Cpu c = rapl.read(first(s.get("cpuname")), first(s.get("cpuvendor")), s.getOrDefault("rapl", List.of()), System.nanoTime(),
          remote.label());
      return new Sample(System.currentTimeMillis(), cores, c, memTotal - memAvailable, memTotal, temp,
          gpus, parseProcesses(s.getOrDefault("apps", List.of()), gpus), diskFree, diskTotal);
    }
  }
}
