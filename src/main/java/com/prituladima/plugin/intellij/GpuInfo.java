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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The NVIDIA GPU, from nvidia-smi, asked once per IDE session in the background; null without one (e.g. macOS). */
final class GpuInfo {
  private static final Pattern CUDA = Pattern.compile("CUDA Version\\s*:?\\s*([0-9.]+)");
  private static final AtomicBoolean ASKED = new AtomicBoolean();
  private static volatile @Nullable Gpu gpu;

  private GpuInfo() {}

  record Gpu(String model, int count, long vramMiB, @Nullable String cuda, String driver) {
    /** {@code GeForce RTX 5060}, with {@code 2× } in front for two such GPUs. */
    String name() {
      return (count > 1 ? count + "× " : "") + model;
    }

    /** {@code VRAM 8 GB}, of one GPU. */
    String vram() {
      return "VRAM " + Math.round(vramMiB / 1024.0) + " GB";
    }

    /** {@code CUDA 13.2}: the newest CUDA the driver supports; null when nvidia-smi does not say. */
    @Nullable String cudaVersion() {
      return cuda == null ? null : "CUDA " + cuda;
    }

    String tooltip() {
      return "NVIDIA " + model + ", " + vramMiB + " MiB, driver " + driver + (cuda == null ? "" : ", CUDA " + cuda);
    }
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

  /** The first GPU of the {@link #QUERY_ARGS} rows and how many there are, plus the CUDA version of plain nvidia-smi. */
  static @Nullable Gpu parse(List<String> rows, List<String> plain) {
    if (rows.isEmpty()) return null;
    String[] first = rows.get(0).split(",\\s*");
    if (first.length < 3) return null;
    long vram;
    try {
      vram = Long.parseLong(first[1].trim());
    } catch (NumberFormatException e) {
      return null;
    }
    String cuda = null;
    for (String line : plain) {
      Matcher m = CUDA.matcher(line);
      if (m.find()) {
        cuda = m.group(1);
        break;
      }
    }
    return new Gpu(first[0].trim().replaceFirst("^NVIDIA ", ""), rows.size(), vram, cuda, first[2].trim());
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
