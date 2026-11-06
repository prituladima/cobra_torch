package com.prituladima.plugin.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import org.jetbrains.annotations.Nullable;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.HardwareAbstractionLayer;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reading per second for the system monitor: every CPU package (its power and temperature) and the load of
 * each of its cores, RAM, every NVIDIA GPU (load, VRAM, temperature, power, model), the processes holding VRAM
 * and their GPU, and every disk with the space used on it.
 * This computer is read through OSHI, nvidia-smi and, on Linux, lsblk; a remote computer (Linux) through one
 * small shell script over the existing SSH connection; {@link Testing} makes a computer up.
 */
abstract class SystemSampler {

  /**
   * {@code cores}: load 0..1 per logical core, null on the first reading (load needs two); {@code corePackage}
   * and {@code coreNumber}: each core's CPU package and its number in the OS ({@code cpu12}), in the same order.
   */
  record Sample(long time, List<Cpu> cpus, double @Nullable [] cores, int[] corePackage, int[] coreNumber,
                long ramUsed, long ramTotal, List<Gpu> gpus, List<GpuProcess> processes, List<Disk> disks) {}

  /**
   * One CPU package (socket): its number, name ({@code Intel(R) Core(TM) i5-14400F}) and maker
   * ({@code GenuineIntel}, {@code AuthenticAMD}), null if unknown; its power and sustained power limit in W, NaN
   * if unknown, with {@code powerWhy} saying why in two words ("root only") and {@code powerNote} in full; its
   * temperature in °C, null if unknown.
   */
  record Cpu(int pkg, @Nullable String name, @Nullable String vendor, double watts, double limitWatts,
             @Nullable String powerWhy, @Nullable String powerNote, @Nullable Double temp) {}

  /**
   * A disk: its model ({@code Samsung SSD 990 PRO 2TB}), size and the bytes used on its file systems mounted
   * here, -1 if none is. {@code physical} is false for the home folder's file system, shown when no disk's use is known.
   */
  record Disk(String name, long size, long used, boolean physical) {}

  /** {@code load} in %, memory in MiB, temperature in °C, power in W; NaN where nvidia-smi says [N/A]. {@code model} without "NVIDIA ". */
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

  // ---- CPU power: RAPL ------------------------------------------------------------------------------------

  private static final String RAPL = "/sys/class/powercap";

  private static String rootOnly(int pkg) {
    return "Linux lets only root read the CPU's energy counter (since the PLATYPUS attack). "
        + "To show the power until the next restart: sudo chmod o+r " + RAPL + "/intel-rapl:" + pkg + "/energy_uj";
  }

  /** A CPU package's power: watts and limit, NaN where unknown, and why in two words and in full. */
  record Power(double watts, double limitWatts, @Nullable String why, @Nullable String note) {
    static Power unknown(String why, String note) {
      return new Power(Double.NaN, Double.NaN, why, note);
    }
  }

  /**
   * The CPU's power from its RAPL energy counters (Intel, and AMD since Linux 5.11), one line per CPU package:
   * {@code name energy_uj max_energy_range_uj constraint_0_power_limit_uw}, name as {@code package-0},
   * {@code -} for a counter that cannot be read. Power is the energy used between two readings over the time between them.
   */
  static final class Rapl {
    private final Map<Integer, Long> last = new HashMap<>();
    private long lastNanos;

    /** The power of each package, by its number; empty without RAPL. */
    Map<Integer, Power> read(List<String> lines, long nanos) {
      Map<Integer, Power> power = new TreeMap<>();
      Map<Integer, Long> now = new HashMap<>();
      double seconds = lastNanos > 0 && nanos > lastNanos ? (nanos - lastNanos) / 1e9 : 0;
      for (int i = 0; i < lines.size(); i++) {
        String[] f = lines.get(i).trim().split("\\s+");
        if (f.length < 4) continue;
        int pkg = f[0].matches("package-\\d+") ? Integer.parseInt(f[0].substring(8)) : i;
        long energy = longOr(f[1], -1), range = longOr(f[2], 0), uw = longOr(f[3], 0);
        double limit = uw > 0 ? uw / 1e6 : Double.NaN;
        if (energy < 0) {
          power.put(pkg, new Power(Double.NaN, limit, "root only", rootOnly(pkg)));
          continue;
        }
        now.put(pkg, energy);
        Long before = last.get(pkg);
        double watts = Double.NaN;
        if (before != null && seconds > 0) {
          long d = energy - before;
          if (d < 0) d += range;   // the counter wrapped
          watts = d / 1e6 / seconds;
        }
        power.put(pkg, new Power(watts, limit, null, null));
      }
      last.clear();
      last.putAll(now);
      lastNanos = nanos;
      return power;
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
      for (Path d : dirs) {
        String n = d.getFileName().toString();
        if (n.indexOf(':') == n.lastIndexOf(':')) packages.add(d);   // intel-rapl:0, not its parts intel-rapl:0:0
      }
      packages.sort(null);
      for (Path d : packages) {
        lines.add(read(d.resolve("name"), "-") + " " + read(d.resolve("energy_uj"), "-") + " "
            + read(d.resolve("max_energy_range_uj"), "0") + " " + read(d.resolve("constraint_0_power_limit_uw"), "0"));
      }
    } catch (IOException | RuntimeException e) {
      // not Linux, or no RAPL here
    }
    return lines;
  }

  // ---- CPU temperature: hwmon -------------------------------------------------------------------------------

  /**
   * The temperature of each CPU package from hwmon lines {@code kind key millidegrees}: {@code coretemp 1 54000}
   * (Intel, key = the package of its "Package id N" sensor), {@code k10temp <device path> 61250} (AMD, one per
   * package, in the order of the device paths), {@code other - 48000} (single-chip boards: package 0).
   */
  static Map<Integer, Double> parseTemps(List<String> lines) {
    Map<Integer, Double> temps = new TreeMap<>();
    TreeMap<String, Double> amd = new TreeMap<>();
    Double other = null;
    for (String line : lines) {
      String[] f = line.trim().split("\\s+");
      if (f.length < 3) continue;
      double c;
      try {
        c = Long.parseLong(f[2]) / 1000.0;
      } catch (NumberFormatException e) {
        continue;
      }
      switch (f[0]) {
        case "coretemp" -> {
          try {
            temps.put(Integer.parseInt(f[1]), c);
          } catch (NumberFormatException ignored) {
            // an unusual label
          }
        }
        case "k10temp" -> amd.put(f[1], c);
        default -> {
          if (other == null) other = c;
        }
      }
    }
    if (temps.isEmpty()) {
      int pkg = 0;
      for (double c : amd.values()) temps.put(pkg++, c);
    }
    if (temps.isEmpty() && other != null) temps.put(0, other);
    return temps;
  }

  /** This computer's hwmon lines, as {@link #parseTemps} reads them; none outside Linux. */
  static List<String> localTemps() {
    List<String> lines = new ArrayList<>();
    try (var dirs = Files.newDirectoryStream(Path.of("/sys/class/hwmon"), "hwmon*")) {
      for (Path d : dirs) {
        String name = read(d.resolve("name"), "");
        switch (name) {
          case "coretemp" -> {
            try (var labels = Files.newDirectoryStream(d, "temp*_label")) {
              for (Path l : labels) {
                String label = read(l, "");
                if (label.startsWith("Package id ") || label.startsWith("Physical id ")) {
                  String input = l.getFileName().toString().replace("_label", "_input");
                  lines.add("coretemp " + label.substring(11).trim() + " " + read(d.resolve(input), "-"));
                }
              }
            }
          }
          case "k10temp", "zenpower" -> lines.add("k10temp " + d.resolve("device").toRealPath() + " " + read(d.resolve("temp1_input"), "-"));
          case "cpu_thermal", "soc_thermal" -> lines.add("other - " + read(d.resolve("temp1_input"), "-"));
          default -> { }
        }
      }
    } catch (IOException | RuntimeException e) {
      // not Linux
    }
    return lines;
  }

  // ---- disks ----------------------------------------------------------------------------------------------

  /**
   * A disk and its file systems, before the space used on them is known: each device on it that holds one
   * (a partition, an LVM or LUKS device) by its name, with the places it is mounted at.
   */
  record BlockDisk(String name, long size, Map<String, List<String>> filesystems) {}

  private static final Pattern PAIR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
  private static final Pattern HEX = Pattern.compile("\\\\x([0-9a-fA-F]{2})");

  /** The command that lists the disks, their partitions and mount points; MOUNTPOINT on util-linux before 2.37. */
  static final String LSBLK = "lsblk -b -P -o NAME,PKNAME,TYPE,MODEL,SIZE,MOUNTPOINTS 2>/dev/null"
      + " || lsblk -b -P -o NAME,PKNAME,TYPE,MODEL,SIZE,MOUNTPOINT 2>/dev/null";

  /**
   * The disks of {@code lsblk -P} rows, each with the mount points of everything on it (partitions, and the LVM,
   * LUKS or RAID devices on those); RAM disks, loop devices and drives without a disc left out.
   */
  static List<BlockDisk> parseLsblk(List<String> rows) {
    record Dev(String name, String type, String model, long size, List<String> mounts) {}
    Map<String, Dev> devs = new LinkedHashMap<>();
    Map<String, Set<String>> children = new HashMap<>();
    for (String row : rows) {
      Map<String, String> f = new HashMap<>();
      Matcher m = PAIR.matcher(row);
      while (m.find()) f.put(m.group(1), unescape(m.group(2)));
      String name = f.get("NAME");
      if (name == null || name.isEmpty()) continue;
      String parent = f.getOrDefault("PKNAME", "");
      if (!parent.isEmpty()) children.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(name);
      if (devs.containsKey(name)) continue;   // a device on several parents comes once per parent
      long size;
      try {
        size = Long.parseLong(f.getOrDefault("SIZE", "0").trim());
      } catch (NumberFormatException e) {
        size = 0;
      }
      String mounts = f.getOrDefault("MOUNTPOINTS", f.getOrDefault("MOUNTPOINT", ""));
      List<String> list = new ArrayList<>();
      for (String mount : mounts.split("\n")) if (mount.startsWith("/")) list.add(mount);
      devs.put(name, new Dev(name, f.getOrDefault("TYPE", ""), f.getOrDefault("MODEL", "").trim().replaceAll("\\s+", " "), size, list));
    }
    List<BlockDisk> disks = new ArrayList<>();
    for (Dev d : devs.values()) {
      if (!d.type().equals("disk") || d.size() <= 0 || d.name().startsWith("zram") || d.name().startsWith("ram")) continue;
      Map<String, List<String>> filesystems = new LinkedHashMap<>();
      Set<String> seen = new HashSet<>();
      List<String> todo = new ArrayList<>(List.of(d.name()));
      while (!todo.isEmpty()) {
        String n = todo.remove(0);
        if (!seen.add(n)) continue;
        Dev dev = devs.get(n);
        if (dev != null && !dev.mounts().isEmpty()) filesystems.put(n, dev.mounts());
        todo.addAll(children.getOrDefault(n, Set.of()));
      }
      disks.add(new BlockDisk(d.model().isEmpty() ? "Disk " + d.name() : d.model(), d.size(), filesystems));
    }
    return disks;
  }

  private static String unescape(String s) {
    Matcher m = HEX.matcher(s);
    StringBuilder sb = new StringBuilder();
    while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
    return m.appendTail(sb).toString();
  }

  /** {@code df -Pk} rows: mount point -> {size, used} in bytes. */
  static Map<String, long[]> parseDf(List<String> rows) {
    Map<String, long[]> usage = new HashMap<>();
    for (String row : rows) {
      String[] f = row.trim().split("\\s+", 6);   // filesystem 1024-blocks used available capacity mount
      if (f.length < 6 || !f[5].startsWith("/")) continue;
      try {
        usage.put(f[5], new long[] {Long.parseLong(f[1]) * 1024, Long.parseLong(f[2]) * 1024});
      } catch (NumberFormatException ignored) {
        // the header
      }
    }
    return usage;
  }

  /**
   * The space used on each disk: the used bytes of its mounted file systems, each counted once however many
   * places it is mounted at; one on several disks (LVM, RAID) counts on each in proportion to the disk's size.
   * -1 where none of a disk's file systems is known.
   */
  static List<Disk> withUsage(List<BlockDisk> disks, Map<String, long[]> usage) {
    Map<String, Long> sharedSize = new HashMap<>();
    for (BlockDisk d : disks) for (String fs : d.filesystems().keySet()) sharedSize.merge(fs, d.size(), Long::sum);
    List<Disk> result = new ArrayList<>();
    for (BlockDisk d : disks) {
      double used = 0;
      boolean known = false;
      for (Map.Entry<String, List<String>> fs : d.filesystems().entrySet()) {
        long[] u = fs.getValue().stream().map(usage::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (u == null) continue;
        known = true;
        used += u[1] * (double) d.size() / sharedSize.get(fs.getKey());
      }
      result.add(new Disk(d.name(), d.size(), known ? Math.round(used) : -1, true));
    }
    return result;
  }

  /** The disks to show: the physical ones, and the home folder's file system when no disk's use is known. */
  static List<Disk> withHome(List<Disk> disks, long homeFree, long homeTotal) {
    if (homeTotal <= 0 || disks.stream().anyMatch(d -> d.used() >= 0)) return disks;
    List<Disk> all = new ArrayList<>(disks);
    all.add(new Disk("Home folder's disk", homeTotal, homeTotal - homeFree, false));
    return all;
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

  // ---- shared parsing -------------------------------------------------------------------------------------

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

  /** The CPU packages, in order, from the package of each core. */
  static List<Cpu> cpus(int[] corePackage, @Nullable String name, @Nullable String vendor, Map<Integer, Power> power,
                        Power noPower, Map<Integer, Double> temps) {
    Set<Integer> packages = new java.util.TreeSet<>();
    for (int p : corePackage) packages.add(p);
    if (packages.isEmpty()) packages.add(0);
    List<Cpu> cpus = new ArrayList<>();
    for (int p : packages) {
      Power w = power.getOrDefault(p, power.isEmpty() ? noPower : Power.unknown("unknown", "No energy counter for this CPU"));
      cpus.add(new Cpu(p, name, vendor, w.watts(), w.limitWatts(), w.why(), w.note(), temps.get(p)));
    }
    return cpus;
  }

  private static String read(Path file, String fallback) {
    try {
      return Files.readString(file).trim();
    } catch (IOException | RuntimeException e) {   // energy_uj: root only
      return fallback;
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
    private static final int DISKS_EVERY = 10;   // seconds; the disks' list every 3 times that
    private final CentralProcessor cpu;
    private final GlobalMemory memory;
    private final HardwareAbstractionLayer hal;
    private final @Nullable String cpuName, cpuVendor;
    private final int[] corePackage, coreNumber;
    private final Rapl rapl = new Rapl();
    private final boolean linux = System.getProperty("os.name", "").startsWith("Linux");
    private long[][] ticks;
    private int tick;
    private List<GpuProcess> processes = List.of();
    private List<BlockDisk> blockDisks = List.of();
    private List<Disk> disks = List.of();

    Local() {
      hal = new SystemInfo().getHardware();
      cpu = hal.getProcessor();
      memory = hal.getMemory();
      ticks = cpu.getProcessorCpuLoadTicks();
      CentralProcessor.ProcessorIdentifier id = cpu.getProcessorIdentifier();
      cpuName = blankToNull(id.getName());
      cpuVendor = blankToNull(id.getVendor());
      List<CentralProcessor.LogicalProcessor> logical = cpu.getLogicalProcessors();
      corePackage = new int[logical.size()];
      coreNumber = new int[logical.size()];
      for (int i = 0; i < logical.size(); i++) {
        corePackage[i] = Math.max(0, logical.get(i).getPhysicalPackageNumber());
        coreNumber[i] = logical.get(i).getProcessorNumber();
      }
    }

    @Override
    String where() {
      return "This Computer";
    }

    @Override
    Sample sample() {
      double[] cores = tick == 0 ? null : cpu.getProcessorCpuLoadBetweenTicks(ticks);
      ticks = cpu.getProcessorCpuLoadTicks();
      List<Gpu> gpus = parseGpus(GpuInfo.nvidiaSmi(GPU_QUERY));
      if (!gpus.isEmpty() && tick % 2 == 0) processes = parseProcesses(GpuInfo.nvidiaSmi(APPS_QUERY), gpus);   // every other second
      if (tick % DISKS_EVERY == 0) disks = disks(tick % (3 * DISKS_EVERY) == 0);
      tick++;

      Map<Integer, Double> temps = linux ? parseTemps(localTemps()) : new TreeMap<>();
      if (temps.isEmpty()) {
        double t = hal.getSensors().getCpuTemperature();
        if (t > 0 && !Double.isNaN(t)) temps.put(0, t);
      }
      List<Cpu> cpus = linux
          ? cpus(corePackage, cpuName, cpuVendor, rapl.read(localRapl(), System.nanoTime()),
              Power.unknown("not offered", "This computer does not tell the CPU's power (no " + RAPL + ")"), temps)
          : cpus(corePackage, cpuName, cpuVendor, Map.of(),
              Power.unknown("not offered", "Windows and macOS do not let programs read the CPU's power"), temps);
      return new Sample(System.currentTimeMillis(), cpus, cores, corePackage, coreNumber,
          memory.getTotal() - memory.getAvailable(), memory.getTotal(), gpus, gpus.isEmpty() ? List.of() : processes, disks);
    }

    /** The disks and the space used on them; their list itself again only when {@code relist}. */
    private List<Disk> disks(boolean relist) {
      if (relist || blockDisks.isEmpty()) blockDisks = linux ? parseLsblk(lsblk()) : oshiDisks();
      Map<String, long[]> usage = new HashMap<>();
      for (BlockDisk d : blockDisks) {
        for (List<String> mounts : d.filesystems().values()) {
          for (String mount : mounts) {
            try {
              FileStore store = Files.getFileStore(Path.of(mount));
              usage.put(mount, new long[] {store.getTotalSpace(), store.getTotalSpace() - store.getUnallocatedSpace()});
              break;   // one place is enough to know the file system
            } catch (IOException | RuntimeException ignored) {
              // gone, or not ours to read
            }
          }
        }
      }
      long[] home = homeDisk();
      return withHome(withUsage(blockDisks, usage), home[0], home[1]);
    }

    private static List<String> lsblk() {
      try {
        ProcessOutput out = ExecUtil.execAndGetOutput(new GeneralCommandLine("sh", "-c", LSBLK), 5000);
        return out.isTimeout() ? List.of() : out.getStdoutLines();
      } catch (ExecutionException e) {
        return List.of();
      }
    }

    /**
     * Windows and macOS: the disks OSHI sees, with their partitions' mount points. On a Mac, the APFS container
     * (model AppleAPFSMedia) holds the mounted volumes of the one physical disk it lives on.
     */
    private List<BlockDisk> oshiDisks() {
      List<BlockDisk> physical = new ArrayList<>();
      Map<String, List<String>> apfs = new LinkedHashMap<>();
      try {
        for (HWDiskStore d : hal.getDiskStores()) {
          if (d.getSize() <= 0) continue;
          Map<String, List<String>> filesystems = new LinkedHashMap<>();
          for (HWPartition p : d.getPartitions()) {
            if (!p.getMountPoint().isBlank()) filesystems.put(d.getName() + "/" + p.getIdentification(), List.of(p.getMountPoint()));
          }
          String model = d.getModel().replace("(Standard disk drives)", "").trim().replaceAll("\\s+", " ");
          if (model.contains("AppleAPFSMedia")) apfs.putAll(filesystems);
          else physical.add(new BlockDisk(model.isEmpty() ? "Disk " + d.getName() : model, d.getSize(), filesystems));
        }
      } catch (RuntimeException e) {
        return List.of();
      }
      List<BlockDisk> unmounted = physical.stream().filter(d -> d.filesystems().isEmpty()).toList();
      if (!apfs.isEmpty() && unmounted.size() == 1) {
        BlockDisk d = unmounted.get(0);
        physical.set(physical.indexOf(d), new BlockDisk(d.name(), d.size(), apfs));
      }
      return physical;
    }
  }

  // ---- the connected remote computer ------------------------------------------------------------------

  static final class Remote extends SystemSampler {
    /** Every second: the loads, memory, temperatures, GPUs and CPU power. Sections marked @@name. */
    private static final String FAST = """
        echo @@cpu; grep '^cpu[0-9]' /proc/stat
        echo @@mem; grep -E '^(MemTotal|MemAvailable):' /proc/meminfo
        echo @@temp
        for d in /sys/class/hwmon/hwmon*; do
          case "$(cat "$d/name" 2>/dev/null)" in
            coretemp)
              for l in "$d"/temp*_label; do
                case "$(cat "$l" 2>/dev/null)" in
                  "Package id "*|"Physical id "*) echo "coretemp $(cut -d' ' -f3 "$l") $(cat "${l%%_label}_input" 2>/dev/null)";;
                esac
              done;;
            k10temp|zenpower) echo "k10temp $(readlink -f "$d/device") $(cat "$d/temp1_input" 2>/dev/null)";;
            cpu_thermal|soc_thermal) echo "other - $(cat "$d/temp1_input" 2>/dev/null)";;
          esac
        done
        echo @@gpu; nvidia-smi %s 2>/dev/null
        echo @@apps; nvidia-smi %s 2>/dev/null
        echo @@rapl
        for d in %s/intel-rapl:*; do
          case "${d##*/}" in *:*:*) continue;; esac
          [ -d "$d" ] || continue
          echo "$(cat "$d/name" 2>/dev/null || echo -) $(cat "$d/energy_uj" 2>/dev/null || echo -) $(cat "$d/max_energy_range_uj" 2>/dev/null || echo 0) $(cat "$d/constraint_0_power_limit_uw" 2>/dev/null || echo 0)"
        done
        """.formatted(String.join(" ", GPU_QUERY), String.join(" ", APPS_QUERY), RAPL);

    /** Every {@link #SLOW_EVERY} seconds as well: what seldom changes, the CPU's name and cores' packages, the disks. */
    private static final String SLOW = """
        echo @@cpuname; grep -m1 -E '^(model name|Model)[[:space:]]*:' /proc/cpuinfo | cut -d: -f2-
        echo @@cpuvendor; grep -m1 '^vendor_id' /proc/cpuinfo | cut -d: -f2-
        echo @@pkg; grep -H . /sys/devices/system/cpu/cpu[0-9]*/topology/physical_package_id 2>/dev/null
        echo @@lsblk; %s
        echo @@df; timeout 5 df -Pkl 2>/dev/null
        echo @@home; df -Pk "$HOME" 2>/dev/null | tail -n 1
        """.formatted(LSBLK);
    private static final int SLOW_EVERY = 10;
    private static final Pattern PKG = Pattern.compile("cpu(\\d+)/topology/physical_package_id:(-?\\d+)");

    private final RemoteHost.Remote remote;
    private final Rapl rapl = new Rapl();
    private long[][] previous;
    private int tick;
    private @Nullable String cpuName, cpuVendor;
    private final Map<Integer, Integer> packageOf = new HashMap<>();
    private List<Disk> disks = List.of();

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
      boolean slow = tick++ % SLOW_EVERY == 0;
      Map<String, List<String>> s = new LinkedHashMap<>();
      List<String> current = null;
      for (String line : SshConnection.lines(remote.ssh.exec(slow ? FAST + SLOW : FAST, null, 8_000).out())) {
        if (line.startsWith("@@")) s.put(line.substring(2), current = new ArrayList<>());
        else if (current != null) current.add(line);
      }
      if (slow) {
        cpuName = first(s.get("cpuname"));
        cpuVendor = first(s.get("cpuvendor"));
        packageOf.clear();
        for (String line : s.getOrDefault("pkg", List.of())) {
          Matcher m = PKG.matcher(line);
          if (m.find()) packageOf.put(Integer.parseInt(m.group(1)), Math.max(0, Integer.parseInt(m.group(2))));
        }
        long homeFree = 0, homeTotal = 0;
        List<String> h = s.getOrDefault("home", List.of());
        if (!h.isEmpty()) {
          String[] f = h.get(0).trim().split("\\s+");   // filesystem 1024-blocks used available capacity mount
          if (f.length >= 4) {
            try {
              homeTotal = Long.parseLong(f[1]) * 1024;
              homeFree = Long.parseLong(f[3]) * 1024;
            } catch (NumberFormatException ignored) {
              // unusual df
            }
          }
        }
        disks = withHome(withUsage(parseLsblk(s.getOrDefault("lsblk", List.of())), parseDf(s.getOrDefault("df", List.of()))),
            homeFree, homeTotal);
      }

      // cpuN user nice system idle iowait irq softirq steal ...: busy is all but idle and iowait
      List<String> cpuRows = s.getOrDefault("cpu", List.of());
      long[][] now = new long[cpuRows.size()][];
      int[] coreNumber = new int[cpuRows.size()], corePackage = new int[cpuRows.size()];
      for (int i = 0; i < cpuRows.size(); i++) {
        String[] f = cpuRows.get(i).split("\\s+");
        coreNumber[i] = Integer.parseInt(f[0].substring(3));
        corePackage[i] = packageOf.getOrDefault(coreNumber[i], 0);
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
      List<Gpu> gpus = parseGpus(s.getOrDefault("gpu", List.of()));
      List<Cpu> cpus = cpus(corePackage, cpuName, cpuVendor, rapl.read(s.getOrDefault("rapl", List.of()), System.nanoTime()),
          Power.unknown("not offered", remote.label() + " does not tell the CPU's power (no " + RAPL + ")"),
          parseTemps(s.getOrDefault("temp", List.of())));
      return new Sample(System.currentTimeMillis(), cpus, cores, corePackage, coreNumber, memTotal - memAvailable, memTotal,
          gpus, parseProcesses(s.getOrDefault("apps", List.of()), gpus), disks);
    }
  }

  // ---- a made-up computer, to see the monitor with a big one -------------------------------------------

  /**
   * Settings | Tools | Cobra torch | Testing: two AMD EPYC 9354 of 32 cores each, 8 NVIDIA H200 and two Samsung
   * 990 PRO 2TB, every measure wandering as on a computer that trains a model.
   */
  static final class Testing extends SystemSampler {
    static final String CPU = "AMD EPYC 9354 32-Core Processor";
    static final int PACKAGES = 2, CORES_EACH = 32, GPUS = 8;
    static final double VRAM_MIB = 143771, GPU_WATTS = 700, CPU_WATTS = 280;
    static final long RAM = 2L << 40, SSD = 2_000_398_934_016L;
    static final GpuInfo.Gpu GPU_INFO = new GpuInfo.Gpu(Collections.nCopies(GPUS, new GpuInfo.Gpu.Card("H200", (long) VRAM_MIB)),
        "12.8", "570.124.06");

    private final Random random = new Random(7);
    private final double[] cores = new double[PACKAGES * CORES_EACH];
    private final double[] loads = new double[GPUS], vram = new double[GPUS], gpuTemps = new double[GPUS];
    private final double[] cpuTemps = new double[PACKAGES];
    private final double[] used = {1.21e12, 0.64e12};
    private double ram = 0.4;

    Testing() {
      for (int i = 0; i < cores.length; i++) cores[i] = 0.3 + 0.6 * random.nextDouble();
      for (int g = 0; g < GPUS; g++) {
        loads[g] = 70 + 25 * random.nextDouble();
        vram[g] = 0.6 + 0.3 * random.nextDouble();
        gpuTemps[g] = 50 + 15 * random.nextDouble();
      }
      for (int p = 0; p < PACKAGES; p++) cpuTemps[p] = 55 + 8 * random.nextDouble();
    }

    @Override
    String where() {
      return "Testing: a made-up computer";
    }

    @Override
    Sample sample() {
      int n = cores.length;
      int[] corePackage = new int[n], coreNumber = new int[n];
      for (int i = 0; i < n; i++) {
        cores[i] = walk(cores[i], 0.12, 0.02, 1);
        corePackage[i] = i / CORES_EACH;
        coreNumber[i] = i;
      }
      List<Cpu> cpus = new ArrayList<>();
      for (int p = 0; p < PACKAGES; p++) {
        double busy = 0;
        for (int i = p * CORES_EACH; i < (p + 1) * CORES_EACH; i++) busy += cores[i] / CORES_EACH;
        cpuTemps[p] = walk(cpuTemps[p], 0.8, 40, 85);
        cpus.add(new Cpu(p, CPU, "AuthenticAMD", 90 + busy * (CPU_WATTS - 100), CPU_WATTS, null, null, cpuTemps[p]));
      }
      List<Gpu> gpus = new ArrayList<>();
      List<GpuProcess> processes = new ArrayList<>();
      for (int g = 0; g < GPUS; g++) {
        loads[g] = walk(loads[g], 6, 0, 100);
        vram[g] = walk(vram[g], 0.01, 0.3, 0.97);
        gpuTemps[g] = walk(gpuTemps[g], 0.6, 35, 83);
        double mem = vram[g] * VRAM_MIB;
        gpus.add(new Gpu(g, "GPU-testing-" + g, loads[g], mem, VRAM_MIB, gpuTemps[g], 120 + loads[g] / 100 * (GPU_WATTS - 140),
            GPU_WATTS, "H200"));
        processes.add(new GpuProcess(g, String.valueOf(40_210 + g), "python train.py --rank " + g, mem - 520));
      }
      ram = walk(ram, 0.004, 0.2, 0.9);
      used[0] = Math.min(SSD, used[0] + 2e7 * random.nextDouble());   // a checkpoint being written
      List<Disk> disks = List.of(new Disk("Samsung SSD 990 PRO 2TB", SSD, Math.round(used[0]), true),
          new Disk("Samsung SSD 990 PRO 2TB", SSD, Math.round(used[1]), true));
      return new Sample(System.currentTimeMillis(), cpus, cores.clone(), corePackage, coreNumber, Math.round(ram * RAM), RAM,
          gpus, processes, disks);
    }

    /** A step of a random walk, kept within [min, max]. */
    private double walk(double v, double step, double min, double max) {
      return Math.max(min, Math.min(max, v + step * (random.nextDouble() * 2 - 1)));
    }
  }
}
