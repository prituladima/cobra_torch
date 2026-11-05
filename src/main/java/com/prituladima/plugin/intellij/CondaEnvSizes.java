package com.prituladima.plugin.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether a conda environment is installed and how much its folder takes, measured in the background
 * and cached. Every few seconds the cheap {@link CondaEnvLocator#signature} of each environment shown
 * recently is compared with the last one; on a change (conda env update / remove, a new env) the folder
 * is measured again and the hints above line 1 of open .conda_env.yml files are refreshed.
 */
@Service(Service.Level.APP)
public final class CondaEnvSizes implements Disposable {
  private static final long POLL_SECONDS = 5;
  private static final long FORGET_AFTER_MS = TimeUnit.MINUTES.toMillis(10);

  record Status(String label, @Nullable String tooltip) {}

  static final Status MEASURING = new Status("Measuring environment size…", null);

  private static final class Entry {
    volatile @Nullable String signature;   // null until measured once
    volatile Status status = MEASURING;
    volatile long lastShown;
    final AtomicBoolean busy = new AtomicBoolean();
  }

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final ScheduledFuture<?> poller;

  public CondaEnvSizes() {
    poller = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
  }

  static CondaEnvSizes getInstance() {
    return ApplicationManager.getApplication().getService(CondaEnvSizes.class);
  }

  /** Never blocks: the cached status, with a measurement started in the background if there is none yet. */
  Status status(String envName) {
    Entry e = entries.computeIfAbsent(envName, k -> new Entry());
    e.lastShown = System.currentTimeMillis();
    if (e.signature == null) measure(envName, e);
    return e.status;
  }

  private void poll() {
    long now = System.currentTimeMillis();
    entries.entrySet().removeIf(x -> now - x.getValue().lastShown > FORGET_AFTER_MS);
    entries.forEach((name, e) -> {
      if (e.signature != null && !Objects.equals(e.signature, CondaEnvLocator.signature(name))) measure(name, e);
    });
  }

  private void measure(String name, Entry e) {
    if (!e.busy.compareAndSet(false, true)) return;
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      try {
        String signature = CondaEnvLocator.signature(name);
        Path prefix = CondaEnvLocator.find(name);
        Status status = prefix == null
            ? new Status("Conda env is not installed", "No conda environment named '" + name + "' was found")
            : new Status("Environment takes " + humanSize(folderSize(prefix)), prefix.toString());
        e.signature = signature;
        if (!status.equals(e.status)) {
          e.status = status;
          refreshHints();
        }
      } finally {
        e.busy.set(false);
      }
    });
  }

  /**
   * Total size of the regular files under the folder; symbolic links are not followed. For the base
   * environment (the conda installation itself) the other environments and the package cache are left out.
   */
  static long folderSize(Path root) {
    long[] total = {0};
    boolean installationRoot = Files.isDirectory(root.resolve("condabin"));
    try {
      Files.walkFileTree(root, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (installationRoot && root.equals(dir.getParent())) {
            String name = dir.getFileName().toString();
            if (name.equals("envs") || name.equals("pkgs")) return FileVisitResult.SKIP_SUBTREE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile()) total[0] += attrs.size();
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException ignored) {
    }
    return total[0];
  }

  /** 1024-based, two decimals: 512.00 KB, 3.41 MB, 2.07 GB. */
  static String humanSize(long bytes) {
    String[] units = {"KB", "MB", "GB", "TB"};
    double value = bytes / 1024.0;
    int unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit++;
    }
    return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
  }

  /** Re-runs the inlay hints of every open .conda_env.yml. */
  private static void refreshHints() {
    ApplicationManager.getApplication().invokeLater(() -> {
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        if (project.isDisposed()) continue;
        for (VirtualFile vf : FileEditorManager.getInstance(project).getOpenFiles()) {
          if (!CondaEnv.isCondaEnvFile(vf)) continue;
          PsiFile psi = ReadAction.computeBlocking(() -> vf.isValid() ? PsiManager.getInstance(project).findFile(vf) : null);
          if (psi != null) DaemonCodeAnalyzer.getInstance(project).restart(psi, "conda environment size changed");
        }
      }
    });
  }

  @Override
  public void dispose() {
    poller.cancel(false);
  }
}
