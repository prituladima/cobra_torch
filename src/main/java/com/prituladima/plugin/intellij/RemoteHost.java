package com.prituladima.plugin.intellij;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The remote computer the toolbar of this project's .conda_env.yml editors works with, while connected:
 * its GPU, its conda environments and their sizes and versions, and the toolbar commands run there.
 */
@Service(Service.Level.PROJECT)
public final class RemoteHost implements Disposable {
  private static final Logger LOG = Logger.getInstance(RemoteHost.class);

  private final Project project;
  private volatile @Nullable Remote remote;

  public RemoteHost(Project project) {
    this.project = project;
  }

  static RemoteHost getInstance(Project project) {
    return project.getService(RemoteHost.class);
  }

  /** The live connection of the project, or null when there is none or it dropped. */
  static @Nullable Remote connected(@Nullable Project project) {
    if (project == null || project.isDisposed()) return null;
    Remote r = getInstance(project).remote;
    return r != null && r.ssh.isConnected() ? r : null;
  }

  /** Blocking: logs in, finds conda and the GPU. Replaces an earlier connection. {@code pricePerHour} in $, 0 if free. */
  void connect(SshConnection.Login login, double pricePerHour) throws Exception {
    disconnect();
    SshConnection ssh = SshConnection.open(login, PathManager.getConfigDir().resolve("cobra-torch").resolve("known_hosts"));
    try {
      remote = Remote.probe(project, ssh, pricePerHour);
    } catch (Exception e) {
      ssh.close();
      throw e;
    }
    CondaEnvRefresh.toolbars();
    CondaEnvRefresh.hints(project);
  }

  void disconnect() {
    Remote r = remote;
    remote = null;
    if (r == null) return;
    r.close();
    CondaEnvRefresh.toolbars();
    CondaEnvRefresh.hints(project);
  }

  @Override
  public void dispose() {
    Remote r = remote;
    remote = null;
    if (r != null) r.close();
  }

  /** One connection and what is known about the computer behind it. */
  static final class Remote implements CondaEnvListDialog.Source {
    private static final Pattern ENVS = Pattern.compile("\"envs\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern JSON_STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final long POLL_SECONDS = 30;
    private static final long FORGET_AFTER_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);   // du of a large environment can take a while

    /** Where conda usually is; also $CONDA_EXE, the PATH and an interactive bash, which runs `conda init`'s lines. */
    private static final String PROBE = """
        find_conda() {
          for c in "$CONDA_EXE" "$HOME/miniforge3/bin/conda" "$HOME/miniconda3/bin/conda" "$HOME/anaconda3/bin/conda" \\
                   "$HOME/mambaforge/bin/conda" /opt/conda/bin/conda /opt/miniforge3/bin/conda /opt/miniconda3/bin/conda \\
                   /opt/anaconda3/bin/conda /usr/local/miniconda3/bin/conda; do
            if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; return; fi
          done
          c=$(command -v conda 2>/dev/null)
          if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; return; fi
          t=""; command -v timeout >/dev/null 2>&1 && t="timeout 10"
          c=$($t bash -ic 'echo "$CONDA_EXE"' 2>/dev/null </dev/null | tail -n 1)
          if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; fi
        }
        echo @@conda; find_conda
        echo @@gpu; nvidia-smi %s 2>/dev/null
        echo @@smi; nvidia-smi 2>/dev/null | head -n 12
        echo @@os; hostname 2>/dev/null; uname -srm 2>/dev/null
        """;

    private final Project project;
    final SshConnection ssh;
    private final @Nullable String conda;
    private final @Nullable String condaRoot;
    private final @Nullable GpuInfo.Gpu gpu;
    private final List<String> os;   // hostname, then the kernel: "gpu-node-7", "Linux 6.8.0-49-generic x86_64"
    private final long connectedAt = System.currentTimeMillis();
    private final double pricePerHour;   // $, as entered at login; 0 for a free one
    private final Map<String, EnvState> envs = new ConcurrentHashMap<>();
    private final ScheduledFuture<?> poller;

    private static final class EnvState {
      volatile CondaEnvSizes.Status status = CondaEnvSizes.MEASURING;
      volatile @Nullable InstalledVersions versions;
      volatile @Nullable String stamp;
      volatile long lastShown;
      volatile boolean fetched;
      final AtomicBoolean busy = new AtomicBoolean();
    }

    private Remote(Project project, SshConnection ssh, @Nullable String conda, @Nullable GpuInfo.Gpu gpu, List<String> os,
                   double pricePerHour) {
      this.project = project;
      this.ssh = ssh;
      this.conda = conda;
      this.condaRoot = conda == null ? null : parent(parent(conda));   // <root>/bin/conda or <root>/condabin/conda
      this.gpu = gpu;
      this.os = List.copyOf(os);
      this.pricePerHour = pricePerHour;
      poller = AppExecutorUtil.getAppScheduledExecutorService()
          .scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    /** The remote path's folder; remote paths always use '/', whatever this computer uses. */
    private static @Nullable String parent(@Nullable String path) {
      int slash = path == null ? -1 : path.lastIndexOf('/');
      return slash > 0 ? path.substring(0, slash) : null;
    }

    static Remote probe(Project project, SshConnection ssh, double pricePerHour) throws Exception {
      SshConnection.Result r = ssh.exec(PROBE.formatted(String.join(" ", GpuInfo.QUERY_ARGS)), null, 60_000);
      Map<String, List<String>> sections = new LinkedHashMap<>();
      List<String> current = null;
      for (String line : SshConnection.lines(r.out())) {
        if (line.startsWith("@@")) sections.put(line.substring(2), current = new ArrayList<>());
        else if (current != null) current.add(line);
      }
      List<String> condaLines = sections.getOrDefault("conda", List.of());
      String conda = condaLines.isEmpty() ? null : condaLines.get(0);
      GpuInfo.Gpu gpu = GpuInfo.parse(sections.getOrDefault("gpu", List.of()), sections.getOrDefault("smi", List.of()));
      return new Remote(project, ssh, conda, gpu, sections.getOrDefault("os", List.of()), pricePerHour);
    }

    String label() {
      return ssh.login().label();
    }

    /** What is known about the connection and the computer, one "name: value" a line, for the disconnect question. */
    List<String[]> facts() {
      SshConnection.Login login = ssh.login();
      List<String[]> facts = new ArrayList<>();
      facts.add(new String[] {"Login", login.user() + "@" + login.host() + ", port " + login.port()});
      if (!os.isEmpty()) facts.add(new String[] {"Computer", String.join(", ", os)});
      if (gpu != null) {
        facts.add(new String[] {"GPU", gpu.name() + ", " + gpu.vram() + (gpu.cudaVersion() == null ? "" : ", " + gpu.cudaVersion())
            + ", driver " + gpu.driver()});
      }
      facts.add(new String[] {"Conda", conda == null ? "not found" : conda});
      if (login.keyFile() != null) facts.add(new String[] {"Key", login.keyFile()});
      facts.add(new String[] {"Connected", "for " + duration(System.currentTimeMillis() - connectedAt)});
      facts.add(new String[] {"Price", price()});
      return facts;
    }

    /** {@code $2.50 per hour, $1.04 so far}; {@code free} for a price of 0. */
    String price() {
      if (pricePerHour <= 0) return "free";
      double hours = (System.currentTimeMillis() - connectedAt) / 3_600_000.0;
      return money(pricePerHour) + " per hour, " + money(pricePerHour * hours) + " so far";
    }

    /** {@code $2.50}; {@code $0.004} for prices under a cent. */
    static String money(double dollars) {
      return dollars > 0 && dollars < 0.01 ? String.format(java.util.Locale.ROOT, "$%.3f", dollars)
          : String.format(java.util.Locale.ROOT, "$%.2f", dollars);
    }

    /** {@code 40 s}, {@code 12 min}, {@code 3 h 5 min}. */
    static String duration(long ms) {
      long s = ms / 1000, min = s / 60, h = min / 60;
      return h > 0 ? h + " h " + min % 60 + " min" : min > 0 ? min + " min" : s + " s";
    }

    @Nullable GpuInfo.Gpu gpu() {
      return gpu;
    }

    /** Never blocks: what is known, with a fetch started when nothing is yet. */
    CondaEnvSizes.Status status(String env) {
      EnvState s = state(env);
      if (!s.fetched) refresh(env);
      return s.status;
    }

    /** Never blocks: null until fetched. */
    @Nullable InstalledVersions versions(String env) {
      EnvState s = state(env);
      if (!s.fetched) refresh(env);
      return s.versions;
    }

    private EnvState state(String env) {
      EnvState s = envs.computeIfAbsent(env, k -> new EnvState());
      s.lastShown = System.currentTimeMillis();
      return s;
    }

    private void poll() {
      if (!ssh.isConnected()) {
        ApplicationManager.getApplication().invokeLater(() -> {
          if (!project.isDisposed() && getInstance(project).remote == this) getInstance(project).disconnect();
        });
        return;
      }
      long now = System.currentTimeMillis();
      envs.entrySet().removeIf(e -> now - e.getValue().lastShown > FORGET_AFTER_MS);
      envs.keySet().forEach(this::refresh);
    }

    /** Measures again when packages changed (conda-meta/history or site-packages), else only checks. */
    private void refresh(String env) {
      EnvState s = envs.computeIfAbsent(env, k -> new EnvState());
      if (!s.busy.compareAndSet(false, true)) return;
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        try {
          CondaEnvSizes.Status before = s.status;
          InstalledVersions versionsBefore = s.versions;
          String prefix = prefixOf(env);
          if (prefix == null) {
            s.status = conda == null
                ? new CondaEnvSizes.Status("Conda is not installed", "No conda found on " + label(), null)
                : new CondaEnvSizes.Status("Conda env is not installed", "No conda environment named '" + env + "' on " + label(), null);
            s.versions = InstalledVersions.NONE;
            s.stamp = null;
          } else {
            String stamp = ssh.exec("stat -c %Y " + SshConnection.quote(prefix + "/conda-meta/history") + " "
                + SshConnection.quote(prefix) + "/lib/python*/site-packages 2>/dev/null | tr '\\n' ' '", null, TIMEOUT_MS).out();
            if (!stamp.equals(s.stamp) || s.status == CondaEnvSizes.MEASURING) {
              long size = size(prefix, prefix.equals(condaRoot));
              s.versions = InstalledVersions.fromCondaListJson(
                  ssh.exec(SshConnection.quote(conda) + " list -p " + SshConnection.quote(prefix) + " --json", null, TIMEOUT_MS).out());
              s.status = new CondaEnvSizes.Status("Conda environment takes " + CondaEnvSizes.humanSize(size),
                  label() + ":" + prefix, null);
              s.stamp = stamp;
            }
          }
          if (!s.status.equals(before)) CondaEnvRefresh.toolbars();
          if (s.versions != versionsBefore) CondaEnvRefresh.hints(project);
        } catch (Exception e) {
          LOG.info("Remote environment " + env + " on " + label() + ": " + e.getMessage());
        } finally {
          s.fetched = true;
          s.busy.set(false);
        }
      });
    }

    /** The environment folders conda knows on the remote computer. */
    private List<String> envPrefixes() throws Exception {
      if (conda == null) return List.of();
      String json = ssh.exec(SshConnection.quote(conda) + " env list --json", null, TIMEOUT_MS).out();
      List<String> prefixes = new ArrayList<>();
      Matcher list = ENVS.matcher(json);
      if (list.find()) {
        Matcher m = JSON_STRING.matcher(list.group(1));
        while (m.find()) prefixes.add(m.group(1).replace("\\\\", "\\").replace("\\/", "/"));
      }
      return prefixes;
    }

    private @Nullable String prefixOf(String env) throws Exception {
      List<String> prefixes = envPrefixes();
      if ("base".equals(env)) return condaRoot != null && prefixes.contains(condaRoot) ? condaRoot : null;
      String inRoot = condaRoot + "/envs/" + env;
      if (prefixes.contains(inRoot)) return inRoot;
      for (String p : prefixes) if (p.endsWith("/" + env)) return p;
      return null;
    }

    /** Bytes in the folder; for base, without the other environments and the package cache. */
    private long size(String prefix, boolean base) throws Exception {
      String q = SshConnection.quote(prefix);
      // apparent sizes, as on this computer; BSD du has no such option and counts blocks
      String exclude = base ? "--exclude=envs --exclude=pkgs " : "";
      String out = ssh.exec("du -sk --apparent-size " + exclude + q + " 2>/dev/null || du -sk " + q + " 2>/dev/null",
          null, TIMEOUT_MS).out();
      String first = out.strip().split("\\s+")[0];
      try {
        return Long.parseLong(first) * 1024;
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    // ---- the toolbar commands, run in a console of the Run tool window ----------------------------------

    void run(@NotNull Project project, CondaEnv.Command cmd, @NotNull VirtualFile file) {
      String env = CondaEnv.envName(file);
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        try {
          String c = conda == null ? "conda" : SshConnection.quote(conda);
          int port = -1;
          String script;
          switch (cmd) {
            case UPDATE -> {
              String dir = "\"$HOME\"/cobra_torch/" + SshConnection.quote(env == null ? "unnamed" : env);
              ssh.exec("mkdir -p " + dir + " && cat > " + dir + "/.conda_env.yml", file.contentsToByteArray(), TIMEOUT_MS);
              script = "cd " + dir + " && " + c + " env update -f .conda_env.yml --prune";
            }
            case JUPYTER -> {
              port = freeLocalPort();
              ssh.forward(port);
              script = "cd \"$HOME\" && " + c + " run --live-stream -n " + SshConnection.quote(String.valueOf(env))
                  + " jupyter lab --no-browser --port=" + port + " --ServerApp.port_retries=0";
            }
            default -> script = c + " env remove -n " + SshConnection.quote(String.valueOf(env));
          }
          int forwarded = port;
          RemoteConsole.run(project, cmd.title(env) + " · " + label(), ssh.terminalChannel(script), () -> {
            if (forwarded > 0) ssh.unforward(forwarded);
            if (env != null) refresh(env);
          });
        } catch (Exception e) {
          RemoteConsole.error(project, cmd.title(env) + " · " + label(), e);
        }
      });
    }

    /** 8888 when it is free here, as Jupyter likes; the remote Jupyter is started on the same port. */
    private static int freeLocalPort() throws IOException {
      for (int p = 8888; p < 8999; p++) {
        try (ServerSocket s = new ServerSocket(p, 1, InetAddress.getLoopbackAddress())) {
          return s.getLocalPort();
        } catch (IOException busy) {
          // next
        }
      }
      try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        return s.getLocalPort();
      }
    }

    // ---- the list of all environments ----------------------------------------------------------------

    @Override
    public String where() {
      return label();
    }

    @Override
    public List<CondaEnvListDialog.Row> list() throws Exception {
      List<CondaEnvListDialog.Row> rows = new ArrayList<>();
      for (String p : envPrefixes()) {
        boolean base = p.equals(condaRoot);
        rows.add(new CondaEnvListDialog.Row(base ? "base" : p.substring(p.lastIndexOf('/') + 1), p, base));
      }
      return rows;
    }

    @Override
    public long size(CondaEnvListDialog.Row row) throws Exception {
      return size(row.prefix(), row.base());
    }

    @Override
    public boolean exists(CondaEnvListDialog.Row row) throws Exception {
      return ssh.exec("[ -d " + SshConnection.quote(row.prefix() + "/conda-meta") + " ]", null, TIMEOUT_MS).exitCode() == 0;
    }

    @Override
    public void remove(Project project, CondaEnvListDialog.Row row) {
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        String title = "conda remove " + row.name() + " · " + label();
        try {
          String c = conda == null ? "conda" : SshConnection.quote(conda);
          RemoteConsole.run(project, title, ssh.terminalChannel(c + " env remove -p " + SshConnection.quote(row.prefix())),
              () -> envs.keySet().forEach(this::refresh));
        } catch (Exception e) {
          RemoteConsole.error(project, title, e);
        }
      });
    }

    void close() {
      poller.cancel(false);
      ssh.close();
    }
  }
}
