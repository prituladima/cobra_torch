package com.prituladima.plugin.intellij;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.AnsiEscapeDecoder;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.jcraft.jsch.ChannelExec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * A remote command's output in the Run tool window, with its own Stop button. What is typed into the
 * console goes to the command (conda's [y]/n question); links such as Jupyter's are clickable.
 */
final class RemoteConsole {
  private RemoteConsole() {}

  /** Connects the channel, shows its console; {@code onExit} runs once the command has ended. */
  static void run(Project project, String title, ChannelExec channel, Runnable onExit) throws Exception {
    InputStream out = channel.getInputStream();   // before connect(), as JSch wants
    OutputStream in = channel.getOutputStream();
    channel.connect(15_000);
    Handler handler = new Handler(channel, out, in);
    handler.addProcessListener(new ProcessListener() {
      @Override
      public void processTerminated(@NotNull ProcessEvent event) {
        onExit.run();
      }
    });
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) {
        handler.destroyProcess();
        return;
      }
      show(project, title, handler);
      handler.startNotify();
    });
  }

  /** A command that could not even start, such as a dropped connection: the reason in a console. */
  static void error(Project project, String title, Exception e) {
    ApplicationManager.getApplication().invokeLater(() -> {
      if (project.isDisposed()) return;
      ConsoleView console = show(project, title, null);
      console.print(String.valueOf(e.getMessage()) + "\n", ConsoleViewContentType.ERROR_OUTPUT);
    });
  }

  private static ConsoleView show(Project project, String title, @Nullable Handler handler) {
    ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
    if (handler != null) console.attachToProcess(handler);
    DefaultActionGroup actions = new DefaultActionGroup();
    if (handler != null) actions.add(new Stop(handler));
    actions.addAll(console.createConsoleActions());
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("CobraTorchRemoteConsole", actions, false);
    JComponent component = console.getComponent();
    toolbar.setTargetComponent(component);
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(toolbar.getComponent(), BorderLayout.WEST);
    panel.add(component, BorderLayout.CENTER);
    RunContentDescriptor descriptor = new RunContentDescriptor(console, handler, panel, title, AllIcons.Nodes.Ssh);
    RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor);
    return console;
  }

  private static final class Stop extends AnAction implements DumbAware {
    private final ProcessHandler handler;

    Stop(ProcessHandler handler) {
      super("Stop", "Stop the remote command", AllIcons.Actions.Suspend);
      this.handler = handler;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      e.getPresentation().setEnabled(!handler.isProcessTerminating() && !handler.isProcessTerminated());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      handler.destroyProcess();
    }
  }

  /** The remote command as a process: its output decoded (UTF-8, ANSI colors), closing the channel stops it. */
  private static final class Handler extends ProcessHandler implements AnsiEscapeDecoder.ColoredTextAcceptor {
    private final ChannelExec channel;
    private final InputStream out;
    private final OutputStream in;
    private final AnsiEscapeDecoder ansi = new AnsiEscapeDecoder();

    Handler(ChannelExec channel, InputStream out, OutputStream in) {
      this.channel = channel;
      this.out = out;
      this.in = in;
    }

    @Override
    public void startNotify() {
      super.startNotify();
      ApplicationManager.getApplication().executeOnPooledThread(this::pump);
    }

    private void pump() {
      char[] buf = new char[8192];
      try (Reader reader = new InputStreamReader(out, StandardCharsets.UTF_8)) {
        int n;
        while ((n = reader.read(buf)) >= 0) {
          if (n > 0) ansi.escapeText(new String(buf, 0, n), ProcessOutputTypes.STDOUT, this);
        }
      } catch (IOException ignored) {
        // the channel was closed
      } finally {
        for (int i = 0; i < 50 && !channel.isClosed(); i++) {
          try {
            Thread.sleep(20);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
        int code = channel.getExitStatus();
        channel.disconnect();
        notifyProcessTerminated(code);
      }
    }

    @Override
    public void coloredTextAvailable(@NotNull String text, @NotNull Key attributes) {
      notifyTextAvailable(text, attributes);
    }

    @Override
    protected void destroyProcessImpl() {
      channel.disconnect();   // with a terminal, the remote process gets SIGHUP; pump() then reports the end
    }

    @Override
    protected void detachProcessImpl() {
      channel.disconnect();
      notifyProcessDetached();
    }

    @Override
    public boolean detachIsDefault() {
      return false;
    }

    @Override
    public @Nullable OutputStream getProcessInput() {
      return in;
    }
  }
}
