package com.prituladima.plugin.intellij;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.execution.util.ExecUtil;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The NVIDIA GPUs, from nvidia-smi, asked once per IDE session in the background; null without one (e.g. macOS). */
final class GpuInfo {
  private static final Pattern CUDA = Pattern.compile("CUDA Version\\s*:?\\s*([0-9.]+)");
  private static final AtomicBoolean ASKED = new AtomicBoolean();
  private static volatile @Nullable Gpu gpu;

  private GpuInfo() {}

  /** Every NVIDIA GPU of a computer, in nvidia-smi's order (never empty). */
  record Gpu(List<Card> cards, @Nullable String cuda, String driver) {
    /** One GPU: its model without the "NVIDIA " in front, and its VRAM. */
    record Card(String model, long vramMiB) {
      long vramGB() {
        return Math.round(vramMiB / 1024.0);
      }
    }

    /** {@code GeForce RTX 5060}; {@code 8 × H100 80GB HBM3}; mixed: {@code 3 × RTX 4090, RTX A6000}. */
    String name() {
      return String.join(", ", groups(cards, Card::model).stream().map(g -> count(g) + g.get(0).model()).toList());
    }

    /**
     * All the VRAM: {@code VRAM 640 GB} for 8 × H100 80GB. Each GPU counts in whole GB (80, not 79.6), and
     * the GPUs of one model as the first of them, since ECC can take a little VRAM from some and not others.
     */
    String vram() {
      return "VRAM " + groups(cards, Card::model).stream().mapToLong(g -> g.size() * g.get(0).vramGB()).sum() + " GB";
    }

    /** {@code CUDA 13.2}: the newest CUDA the driver supports; null when nvidia-smi does not say. */
    @Nullable String cudaVersion() {
      return cuda == null ? null : "CUDA " + cuda;
    }

    /** {@code NVIDIA 8 × H100 80GB HBM3, 81559 MiB each, driver 570.124.06, CUDA 12.8} */
    String tooltip() {
      List<String> parts = new ArrayList<>();
      for (List<Card> g : groups(cards, Card::model)) {
        parts.add(count(g) + g.get(0).model() + ", " + g.get(0).vramMiB() + " MiB" + (g.size() > 1 ? " each" : ""));
      }
      return "NVIDIA " + String.join("; ", parts) + ", driver " + driver + (cuda == null ? "" : ", CUDA " + cuda);
    }

    private static String count(List<Card> group) {
      return group.size() > 1 ? group.size() + " × " : "";
    }
  }

  /**
   * The GPUs of one model together; the biggest group first, then in nvidia-smi's order.
   * {@code [A, B, B, B]} gives {@code [[B, B, B], [A]]}.
   */
  static <T> List<List<T>> groups(List<T> items, Function<T, String> model) {
    Map<String, List<T>> byKey = new LinkedHashMap<>();
    for (T item : items) byKey.computeIfAbsent(model.apply(item), k -> new ArrayList<>()).add(item);
    List<List<T>> groups = new ArrayList<>(byKey.values());
    groups.sort(Comparator.comparingInt(g -> -g.size()));   // stable: equal sizes keep their order
    return groups;
  }

  /** {@code 8 × H100 80GB HBM3}, or {@code 3 × RTX 4090, RTX A6000}, from the models of the GPUs in order. */
  static String groupedName(List<String> models) {
    return String.join(", ", groups(models, m -> m).stream().map(g -> (g.size() > 1 ? g.size() + " × " : "") + g.get(0)).toList());
  }

  /** Never blocks: null until nvidia-smi has answered, and for good without an NVIDIA GPU. */
  static @Nullable Gpu get() {
    if (ASKED.compareAndSet(false, true)) AppExecutorUtil.getAppExecutorService().execute(GpuInfo::ask);
    return gpu;
  }

  private static void ask() {
    List<String> rows = nvidiaSmi(QUERY_ARGS);
    if (rows.isEmpty()) return;
    gpu = parse(rows, nvidiaSmi());
    if (gpu != null) ActivityTracker.getInstance().inc();   // open toolbars pick it up
  }

  /** The arguments of the one-line-per-GPU query; also used on the remote computer. */
  static final String[] QUERY_ARGS = {"--query-gpu=name,memory.total,driver_version", "--format=csv,noheader,nounits"};

  /** Every GPU of the {@link #QUERY_ARGS} rows, plus the CUDA version of plain nvidia-smi; null without a GPU. */
  static @Nullable Gpu parse(List<String> rows, List<String> plain) {
    List<Gpu.Card> cards = new ArrayList<>();
    String driver = "";
    for (String row : rows) {
      int last = row.lastIndexOf(','), memory = last < 0 ? -1 : row.lastIndexOf(',', last - 1);
      if (memory <= 0) continue;
      try {
        long vram = Long.parseLong(row.substring(memory + 1, last).trim());
        cards.add(new Gpu.Card(model(row.substring(0, memory)), vram));
        driver = row.substring(last + 1).trim();
      } catch (NumberFormatException ignored) {
        // [N/A] or a line that is not a GPU
      }
    }
    if (cards.isEmpty()) return null;
    String cuda = null;
    for (String line : plain) {
      Matcher m = CUDA.matcher(line);
      if (m.find()) {
        cuda = m.group(1);
        break;
      }
    }
    return new Gpu(List.copyOf(cards), cuda, driver);
  }

  /** {@code NVIDIA H100 80GB HBM3} -> {@code H100 80GB HBM3}; the name as nvidia-smi gives it, without the maker. */
  static String model(String name) {
    return name.trim().replaceFirst("^NVIDIA ", "");
  }

  static List<String> nvidiaSmi(String... args) {
    for (String exe : executables()) {
      try {
        ProcessOutput out = ExecUtil.execAndGetOutput(new GeneralCommandLine(exe).withParameters(args), 5000);
        if (!out.isTimeout() && out.getExitCode() == 0) {
          return out.getStdoutLines().stream().filter(line -> !line.isBlank()).toList();
        }
      } catch (ExecutionException ignored) {
        // not installed there
      }
    }
    return List.of();
  }

  /** nvidia-smi on PATH; on Windows also the older driver location. */
  private static List<String> executables() {
    List<String> exes = new ArrayList<>(List.of("nvidia-smi"));
    String programFiles = System.getenv("ProgramFiles");
    if (SystemInfo.isWindows && programFiles != null) exes.add(programFiles + "\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe");
    return exes;
  }
}
