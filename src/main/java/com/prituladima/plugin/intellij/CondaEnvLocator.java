package com.prituladima.plugin.intellij;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a conda environment's folder by name without running conda, on Linux, macOS and Windows:
 * {@code envs_dirs} from .condarc and the environment, {@code <root>/envs} of every conda installation
 * found, {@code ~/.conda/envs}, and conda's own registry {@code ~/.conda/environments.txt}.
 */
final class CondaEnvLocator {
  private static final Pattern LIST_ITEM = Pattern.compile("^[ \\t]*-[ \\t]*['\"]?([^'\"#]+?)['\"]?[ \\t]*(?:#.*)?$");
  private static final List<String> INSTALL_NAMES =
      List.of("miniconda3", "anaconda3", "miniforge3", "mambaforge", "micromamba", "miniconda", "anaconda");

  private CondaEnvLocator() {}

  /** The environment's folder, or null when no installed environment has this name. */
  static @Nullable Path find(String name) {
    if ("base".equals(name)) {
      for (Path root : roots()) if (isEnv(root)) return root;
      return null;
    }
    for (Path dir : envsDirs()) {
      Path p = dir.resolve(name);
      if (isEnv(p)) return p;
    }
    for (Path p : registered()) {
      if (p.getFileName() != null && p.getFileName().toString().equals(name) && isEnv(p)) return p;
    }
    return null;
  }

  /** Changes whenever the environment appears, disappears, moves, or has packages installed or removed. */
  static String signature(String name) {
    Path p = find(name);
    if (p == null) return "absent";
    long stamp;
    try {
      stamp = Files.getLastModifiedTime(p.resolve("conda-meta").resolve("history")).toMillis();
    } catch (IOException e) {
      stamp = -1;
    }
    return p + "|" + stamp;
  }

  private static boolean isEnv(Path p) {
    return Files.isDirectory(p.resolve("conda-meta"));
  }

  private static Path home() {
    return Path.of(System.getProperty("user.home"));
  }

  private static Set<Path> envsDirs() {
    Set<Path> dirs = new LinkedHashSet<>();
    for (String var : List.of("CONDA_ENVS_PATH", "CONDA_ENVS_DIRS")) {
      String value = System.getenv(var);
      if (value != null) for (String part : value.split(File.pathSeparator)) addPath(dirs, part);
    }
    dirs.addAll(condarcEnvsDirs());
    for (Path root : roots()) dirs.add(root.resolve("envs"));
    dirs.add(home().resolve(".conda").resolve("envs"));
    return dirs;
  }

  /** Conda installations: from the IDE's environment, conda's registry, and the usual install locations. */
  private static Set<Path> roots() {
    Set<Path> roots = new LinkedHashSet<>();
    String exe = System.getenv("CONDA_EXE");   // <root>/bin/conda, <root>/condabin/conda, <root>\Scripts\conda.exe
    if (exe != null) {
      Path p = toPath(exe);
      if (p != null && p.getParent() != null && p.getParent().getParent() != null) roots.add(p.getParent().getParent());
    }
    for (String var : List.of("CONDA_ROOT", "MAMBA_ROOT_PREFIX", "CONDA_PREFIX")) {
      Path p = toPath(System.getenv(var));
      if (p != null) roots.add(rootOf(p));
    }
    for (Path p : registered()) roots.add(rootOf(p));

    List<Path> bases = new ArrayList<>(List.of(home()));
    for (String var : List.of("LOCALAPPDATA", "ProgramData", "ProgramFiles")) {
      Path p = toPath(System.getenv(var));
      if (p != null) bases.add(p);
    }
    bases.add(Path.of(File.separator, "opt"));
    for (Path base : bases) for (String n : INSTALL_NAMES) roots.add(base.resolve(n));
    for (String p : List.of("/opt/conda", "/opt/homebrew/Caskroom/miniconda/base", "/usr/local/Caskroom/miniconda/base",
        "/opt/homebrew/Caskroom/miniforge/base", "/usr/local/Caskroom/miniforge/base")) addPath(roots, p);

    roots.removeIf(p -> !Files.isDirectory(p));
    return roots;
  }

  /** An environment inside {@code <root>/envs} belongs to that root; anything else is a root itself. */
  private static Path rootOf(Path env) {
    Path parent = env.getParent();
    if (parent != null && parent.getFileName() != null && "envs".equals(parent.getFileName().toString())
        && parent.getParent() != null) {
      return parent.getParent();
    }
    return env;
  }

  /** ~/.conda/environments.txt: every environment conda has created, one folder per line. */
  private static List<Path> registered() {
    List<Path> envs = new ArrayList<>();
    for (String line : readLines(home().resolve(".conda").resolve("environments.txt"))) addPath(envs, line.trim());
    return envs;
  }

  /** The {@code envs_dirs:} list of ~/.condarc and ~/.config/conda/condarc. */
  private static List<Path> condarcEnvsDirs() {
    List<Path> dirs = new ArrayList<>();
    for (Path rc : List.of(home().resolve(".condarc"), home().resolve(".config").resolve("conda").resolve("condarc"))) {
      boolean inList = false;
      for (String line : readLines(rc)) {
        if (line.startsWith("envs_dirs:")) {
          inList = true;
          continue;
        }
        if (!inList || line.isBlank() || line.trim().startsWith("#")) continue;
        Matcher m = LIST_ITEM.matcher(line);
        if (!m.matches()) {
          inList = false;
          continue;
        }
        String value = m.group(1).trim();
        if (value.startsWith("~")) value = home() + value.substring(1);
        addPath(dirs, value);
      }
    }
    return dirs;
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.isRegularFile(file) ? Files.readAllLines(file) : List.of();
    } catch (IOException e) {
      return List.of();
    }
  }

  private static void addPath(java.util.Collection<Path> into, @Nullable String value) {
    Path p = toPath(value);
    if (p != null) into.add(p);
  }

  private static @Nullable Path toPath(@Nullable String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Path.of(value.trim());
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
