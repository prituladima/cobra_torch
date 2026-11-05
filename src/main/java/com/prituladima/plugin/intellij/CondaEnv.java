package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab;
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.datatransfer.StringSelection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The .conda_env.yml file: its name, its env name, and the conda commands run from the gutter. */
final class CondaEnv {
  static final String FILE_NAME = ".conda_env.yml";

  private static final Logger LOG = Logger.getInstance(CondaEnv.class);
  private static final Pattern ENV_NAME = Pattern.compile("^name:[ \\t]*['\"]?([^'\"#\\s]+)", Pattern.MULTILINE);
  private static final Pattern SHELL_SAFE = Pattern.compile("[\\w.+-]+");

  private CondaEnv() {}

  /** One gutter button: the line (0-based) it sits on, its icon, and the command it runs. */
  enum Command {
    UPDATE(0, AllIcons.Actions.Refresh, "Update conda environment"),
    REMOVE(1, AllIcons.Actions.GC, "Remove conda environment");   // GC renders as a trash bin

    final int line;
    final Icon icon;
    final String title;

    Command(int line, Icon icon, String title) {
      this.line = line;
      this.icon = icon;
      this.title = title;
    }

    /**
     * The shell command for this file, or null when REMOVE cannot find `name:`.
     * conda has no `env remove -f <file>`, so REMOVE uses the env name from the file.
     */
    @Nullable String commandLine(@NotNull VirtualFile file) {
      if (this == UPDATE) return "conda env update -f " + quote(file.getName()) + " --prune";
      String env = envName(file);
      return env == null ? null : "conda env remove -n " + quote(env);
    }

    String tooltip(PsiElement anchor) {
      VirtualFile file = fileOf(anchor);
      String cmd = file == null ? null : commandLine(file);
      return cmd == null ? title : title + ": " + cmd;
    }

    void run(PsiElement anchor) {
      VirtualFile file = fileOf(anchor);
      if (file == null || file.getParent() == null) return;
      Project project = anchor.getProject();

      FileDocumentManager fdm = FileDocumentManager.getInstance();
      Document doc = fdm.getDocument(file);
      if (doc != null) WriteIntentReadAction.run((Runnable) () -> fdm.saveDocument(doc));  // conda reads the disk copy

      String cmd = commandLine(file);
      if (cmd == null) {
        Messages.showErrorDialog(project, "No top-level 'name:' found in " + file.getPresentableUrl(), title);
        return;
      }
      String env = envName(file);
      String tab = "conda " + name().toLowerCase(Locale.ROOT) + (env == null ? "" : " " + env);
      runInTerminal(project, nativePath(file.getParent()), tab, cmd, title);
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
   * missing API falls back to the older one, and then to putting the command on the clipboard.
   */
  static void runInTerminal(Project project, String workDir, String tabName, String command, String title) {
    try {
      ReworkedTerminal.run(project, workDir, tabName, command);
      return;
    } catch (LinkageError e) {
      LOG.info("Terminal tabs API unavailable, trying the older terminal API", e);
    }
    try {
      ClassicTerminal.run(project, workDir, tabName, command);
      return;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      LOG.warn("No usable terminal API", e);
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(command));
    Messages.showInfoMessage(project,
        "Could not open a Terminal tab. Run this in " + workDir + " (it is on the clipboard):\n\n" + command, title);
  }

  /** OS-native path (C:\... on Windows), which the terminal expects as its working directory. */
  private static String nativePath(VirtualFile dir) {
    try {
      return dir.toNioPath().toString();
    } catch (UnsupportedOperationException e) {
      return dir.getPath();
    }
  }

  private static @Nullable VirtualFile fileOf(PsiElement anchor) {
    return ReadAction.computeBlocking(() -> {
      if (!anchor.isValid()) return null;
      PsiFile psi = anchor.getContainingFile();
      return psi == null ? null : psi.getViewProvider().getVirtualFile();
    });
  }

  /** Bare when safe, else double quotes: understood by bash, zsh, fish, PowerShell and cmd alike. */
  private static String quote(String s) {
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
