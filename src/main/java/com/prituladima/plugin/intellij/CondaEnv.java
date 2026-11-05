package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The .conda_env.yml file: its name, its env name, and the conda commands of its toolbar. */
final class CondaEnv {
  static final String FILE_NAME = ".conda_env.yml";

  private static final Logger LOG = Logger.getInstance(CondaEnv.class);
  private static final Pattern ENV_NAME = Pattern.compile("^name:[ \\t]*['\"]?([^'\"#\\s]+)", Pattern.MULTILINE);
  private static final Pattern SHELL_SAFE = Pattern.compile("[\\w.+-]+");

  private CondaEnv() {}

  /**
   * A toolbar button: one plain conda command, run in a new Terminal tab in the file's directory.
   * Nothing is checked first: if the environment does not exist, conda says so in the terminal.
   */
  enum Command {
    UPDATE(AllIcons.Actions.Refresh, "Update environment", "update"),
    JUPYTER(KnownPackage.JUPYTERLAB.icon(), "Open JupyterLab", "jupyter"),
    REMOVE(AllIcons.Actions.GC, "Remove environment", "remove");   // GC renders as a trash bin

    final Icon icon;
    final String title;
    private final String tab;

    Command(Icon icon, String title, String tab) {
      this.icon = icon;
      this.title = title;
      this.tab = tab;
    }

    /** The shell command, or null when JUPYTER / REMOVE have no top-level `name:` to use. */
    @Nullable String commandLine(@NotNull VirtualFile file) {
      if (this == UPDATE) return "conda env update -f " + quote(file.getName()) + " --prune";
      String env = envName(file);
      if (env == null) return null;
      return this == JUPYTER
          ? "conda run --live-stream -n " + quote(env) + " jupyter lab"
          : "conda env remove -n " + quote(env);
    }

    /** The command as shown in the button's tooltip: here, or on the connected remote computer. */
    @Nullable String describe(@NotNull Project project, @NotNull VirtualFile file) {
      String cmd = commandLine(file);
      RemoteHost.Remote remote = RemoteHost.connected(project);
      return cmd == null || remote == null ? cmd : remote.label() + ": " + cmd + (this == JUPYTER ? " --no-browser, through an SSH tunnel" : "");
    }

    /** The tab or console title, such as "conda update my-env". */
    String title(@Nullable String env) {
      return "conda " + tab + (env == null ? "" : " " + env);
    }

    void run(@NotNull Project project, @NotNull VirtualFile file) {
      VirtualFile dir = file.getParent();
      String cmd = commandLine(file);
      if (dir == null || cmd == null) return;
      FileDocumentManager fdm = FileDocumentManager.getInstance();
      Document doc = fdm.getDocument(file);
      if (doc != null) WriteIntentReadAction.run((Runnable) () -> fdm.saveDocument(doc));   // conda reads the disk copy
      RemoteHost.Remote remote = RemoteHost.connected(project);
      if (remote != null) {
        remote.run(project, this, file);
        return;
      }
      runInTerminal(project, nativePath(dir), title(envName(file)), cmd);
    }
  }

  static boolean isCondaEnvFile(@Nullable VirtualFile file) {
    return file != null && FILE_NAME.equals(file.getName());
  }

  static @Nullable String envName(@NotNull VirtualFile file) {
    return ReadAction.computeBlocking(() -> {
      Document doc = FileDocumentManager.getInstance().getDocument(file);
      return doc == null ? null : envName(doc.getCharsSequence());
    });
  }

  /** The top-level {@code name:} of an environment file's text. */
  static @Nullable String envName(CharSequence text) {
    Matcher m = ENV_NAME.matcher(text);
    return m.find() ? m.group(1) : null;
  }

  /**
   * Runs the command in a new Terminal tab. The terminal API has changed across IDE versions, so a
   * missing API falls back to the older one; with neither, the command is only logged.
   */
  static void runInTerminal(Project project, String workDir, String tabName, String command) {
    try {
      ReworkedTerminal.run(project, workDir, tabName, command);
      return;
    } catch (LinkageError e) {
      LOG.info("Terminal tabs API unavailable, trying the older terminal API", e);
    }
    try {
      ClassicTerminal.run(project, workDir, tabName, command);
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      LOG.warn("No usable terminal API, not run: " + command, e);
    }
  }

  /** OS-native path (C:\... on Windows), which the terminal expects as its working directory. */
  static String nativePath(VirtualFile dir) {
    try {
      return dir.toNioPath().toString();
    } catch (UnsupportedOperationException e) {
      return dir.getPath();
    }
  }

  /** Bare when safe, else double quotes: understood by bash, zsh, fish, PowerShell and cmd alike. */
  static String quote(String s) {
    return SHELL_SAFE.matcher(s).matches() ? s : '"' + s + '"';
  }

  /**
   * The terminal tabs API (IntelliJ 2025.3+, experimental). Kept in its own class so that, if a later
   * IDE drops it, the failure is a LinkageError at this call rather than CondaEnv failing to load.
   */
  private static final class ReworkedTerminal {
    static void run(Project project, String workDir, String tabName, String command) {
      TerminalToolWindowTab tab = TerminalToolWindowTabsManager.getInstance(project)
          .createTabBuilder()
          .workingDirectory(workDir)
          .tabName(tabName)
          .requestFocus(true)          // also opens the Terminal tool window
          .createTab();
      // input is buffered until the shell has started, so sending right away is safe
      tab.getView().createSendTextBuilder().shouldExecute().send(command);
    }
  }

  /** The older terminal API, deprecated in 2026.x; looked up reflectively so its removal cannot break loading. */
  private static final class ClassicTerminal {
    static void run(Project project, String workDir, String tabName, String command) throws ReflectiveOperationException {
      ClassLoader loader = CondaEnv.class.getClassLoader();
      Class<?> manager = Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager", true, loader);
      Object instance = manager.getMethod("getInstance", Project.class).invoke(null, project);
      Object widget = manager.getMethod("createShellWidget", String.class, String.class, boolean.class, boolean.class)
          .invoke(instance, workDir, tabName, true, true);
      Class.forName("com.intellij.terminal.ui.TerminalWidget", true, loader)
          .getMethod("sendCommandToExecute", String.class)
          .invoke(widget, command);
    }
  }
}
