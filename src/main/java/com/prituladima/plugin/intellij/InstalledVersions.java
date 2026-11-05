package com.prituladima.plugin.intellij;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The package versions installed in a conda environment: conda packages from {@code conda-meta/*.json},
 * pip packages from {@code site-packages/*.dist-info}, or, for a remote computer, from
 * {@code conda list --json}. Names are compared the pip way: case and runs of {@code -_.} do not matter.
 */
record InstalledVersions(Map<String, String> conda, Map<String, String> pip) {
  static final InstalledVersions NONE = new InstalledVersions(Map.of(), Map.of());

  private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*}");
  private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

  private record Cached(String stamp, InstalledVersions versions) {}

  /** The installed version of a file item: a conda item prefers the conda package, a pip item the pip one. */
  @Nullable String version(EnvFileItems.Item item) {
    String key = normalize(item.name());
    Map<String, String> first = item.pip() ? pip : conda;
    Map<String, String> second = item.pip() ? conda : pip;
    String v = first.get(key);
    return v != null ? v : second.get(key);
  }

  static String normalize(String name) {
    return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
  }

  /** The local environment's versions, read again only after packages were installed or removed. */
  static InstalledVersions local(Path prefix) {
    String stamp = CondaEnvLocator.stamp(prefix);
    Cached cached = CACHE.get(prefix);
    if (cached != null && cached.stamp().equals(stamp)) return cached.versions();
    InstalledVersions v = new InstalledVersions(condaMeta(prefix), distInfo(prefix));
    CACHE.put(prefix, new Cached(stamp, v));
    return v;
  }

  /** {@code conda-meta/<name>-<version>-<build>.json}; names may contain dashes, versions and builds do not. */
  private static Map<String, String> condaMeta(Path prefix) {
    Map<String, String> versions = new HashMap<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(prefix.resolve("conda-meta"), "*.json")) {
      for (Path f : files) {
        String stem = f.getFileName().toString();
        stem = stem.substring(0, stem.length() - ".json".length());
        int build = stem.lastIndexOf('-');
        int version = build > 0 ? stem.lastIndexOf('-', build - 1) : -1;
        if (version > 0) versions.put(normalize(stem.substring(0, version)), stem.substring(version + 1, build));
      }
    } catch (IOException ignored) {
      // not an environment (any more)
    }
    return versions;
  }

  /** {@code site-packages/<name>-<version>.dist-info}; a version never contains a dash. */
  private static Map<String, String> distInfo(Path prefix) {
    Map<String, String> versions = new HashMap<>();
    for (Path sp : CondaEnvLocator.sitePackages(prefix)) {
      try (DirectoryStream<Path> dirs = Files.newDirectoryStream(sp, "*.dist-info")) {
        for (Path d : dirs) {
          String stem = d.getFileName().toString();
          stem = stem.substring(0, stem.length() - ".dist-info".length());
          int dash = stem.lastIndexOf('-');
          if (dash > 0) versions.put(normalize(stem.substring(0, dash)), stem.substring(dash + 1));
        }
      } catch (IOException ignored) {
        // no such folder
      }
    }
    return versions;
  }

  /** The output of {@code conda list --json}: pip packages are the ones from the "pypi" channel. */
  static InstalledVersions fromCondaListJson(String json) {
    Map<String, String> conda = new HashMap<>();
    Map<String, String> pip = new HashMap<>();
    Matcher m = JSON_OBJECT.matcher(json);
    while (m.find()) {
      String obj = m.group();
      String name = jsonField(obj, "name");
      String version = jsonField(obj, "version");
      if (name == null || version == null) continue;
      ("pypi".equals(jsonField(obj, "channel")) ? pip : conda).put(normalize(name), version);
    }
    return new InstalledVersions(conda, pip);
  }

  static @Nullable String jsonField(String obj, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
    return m.find() ? m.group(1).replace("\\\\", "\\").replace("\\/", "/").replace("\\\"", "\"") : null;
  }
}
