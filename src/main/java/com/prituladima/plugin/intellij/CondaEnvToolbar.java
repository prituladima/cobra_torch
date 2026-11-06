package com.prituladima.plugin.intellij;

import com.intellij.icons.AllIcons;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The toolbar at the top of every .conda_env.yml editor, like the Jupyter notebook toolbar:
 * conda environment size | this computer or the remote one | NVIDIA GPU | VRAM | CUDA | update, open JupyterLab,
 * open the folder, remove. While connected over SSH, all of it is about the remote computer ({@link RemoteHost}).
 * Without an NVIDIA GPU its items hide, and the platform drops the separators left doubled.
 * Registered as a project listener on editor openings (META-INF/plugin.xml).
 */
public final class CondaEnvToolbar implements FileEditorManagerListener {
  private static final Key<Boolean> INSTALLED = Key.create("cobratorch.condaEnvToolbar");

  @Override
  public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    install(source, file);
  }

  /** Also covers editors that open later for the same file, such as a split view. */
  @Override
  public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    VirtualFile file = event.getNewFile();
    if (file != null) install(event.getManager(), file);
  }

  private static void install(FileEditorManager manager, VirtualFile file) {
    if (!CondaEnv.isCondaEnvFile(file)) return;
    for (FileEditor editor : manager.getAllEditorList(file)) {
      if (editor.getUserData(INSTALLED) != null) continue;
      editor.putUserData(INSTALLED, Boolean.TRUE);
      manager.addTopComponent(editor, toolbar(manager.getProject(), file, editor));
    }
  }

  private static JComponent toolbar(Project project, VirtualFile file, FileEditor editor) {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(new EnvSizeLabel(project, file));
    group.addSeparator();
    group.add(new WhereLabel(project));
    group.addSeparator();
    group.add(new GpuLabel(project, CondaIcons.NVIDIA, GpuInfo.Gpu::name));
    group.addSeparator();
    group.add(new GpuLabel(project, null, GpuInfo.Gpu::vram));
    group.addSeparator();
    group.add(new GpuLabel(project, null, GpuInfo.Gpu::cudaVersion));
    group.addSeparator();
    group.add(new CommandButton(CondaEnv.Command.UPDATE, file));
    group.add(new CommandButton(CondaEnv.Command.JUPYTER, file));
    group.add(new FolderButton(file));
    group.add(new CommandButton(CondaEnv.Command.REMOVE, file));
    ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("CobraTorchCondaEnvToolbar", group, true);
    toolbar.setTargetComponent(editor.getComponent());
    JComponent component = toolbar.getComponent();
    component.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(1, 4)));
    return component;
  }

  /** A hand cursor, and a left click runs the action. */
  private static JComponent clickable(JComponent c, Runnable onClick) {
    c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    c.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1) onClick.run();
      }
    });
    return c;
  }

  /** A text item of the toolbar; the text is set without mnemonics, so env names may contain {@code _}. */
  private abstract static class Label extends AnAction implements CustomComponentAction, DumbAware {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
      JBLabel label = new JBLabel();
      label.setBorder(JBUI.Borders.empty(0, 6));
      label.setIconTextGap(JBUI.scale(4));
      return label;
    }

    @Override
    public void updateCustomComponent(@NotNull JComponent component, @NotNull Presentation presentation) {
      JBLabel label = (JBLabel) component;
      label.setText(presentation.getText());
      label.setIcon(presentation.getIcon());
      label.setToolTipText(presentation.getDescription());
    }
  }

  /**
   * Conda logo + "Conda environment takes 7.10 GB" / "Conda env is not installed" ({@link CondaEnvSizes}, or
   * {@link RemoteHost} while connected). A click lists all environments ({@link CondaEnvListDialog}).
   */
  private static final class EnvSizeLabel extends Label {
    private final Project project;
    private final VirtualFile file;

    EnvSizeLabel(Project project, VirtualFile file) {
      this.project = project;
      this.file = file;
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
      return clickable(super.createCustomComponent(presentation, place), () -> {
        if (!project.isDisposed()) CondaEnvListDialog.show(project);
      });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation p = e.getPresentation();
      String env = CondaEnv.envName(file);
      p.setVisible(env != null);
      if (env == null) return;
      RemoteHost.Remote remote = RemoteHost.connected(project);
      CondaEnvSizes.Status status = remote != null ? remote.status(env) : CondaEnvSizes.getInstance().status(env);
      p.setIcon(CondaIcons.CONDA);
      p.setText(status.label(), false);
      p.setDescription((status.tooltip() == null ? "" : status.tooltip() + ". ") + "Click to see all conda environments");
    }
  }

  /**
   * Which computer the toolbar is about: "This computer", or "Remote computer" while connected over SSH. A click
   * on "This computer" connects (the SSH login dialog); on "Remote computer", it shows the connection and asks
   * whether to disconnect.
   */
  private static final class WhereLabel extends Label {
    private final Project project;

    WhereLabel(Project project) {
      this.project = project;
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
      return clickable(super.createCustomComponent(presentation, place), () -> {
        if (project.isDisposed()) return;
        RemoteHost.Remote remote = RemoteHost.connected(project);
        if (remote == null) new RemoteLoginDialog(project).show();
        else askDisconnect(project, remote);
      });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      RemoteHost.Remote remote = RemoteHost.connected(project);
      Presentation p = e.getPresentation();
      if (remote == null) {
        p.setIcon(AllIcons.Nodes.Desktop);
        p.setText("This computer", false);
        p.setDescription("The toolbar is about this computer. Click to connect to a remote computer over SSH");
      } else {
        p.setIcon(AllIcons.Webreferences.Server);
        p.setText("Remote computer", false);
        p.setDescription(remote.label() + " over SSH, " + remote.price() + ": the toolbar is about it, and its commands run there. "
            + "Click to see the connection");
      }
    }

    /** The connection's details, then [Disconnect] [Keep the Connection]. */
    private static void askDisconnect(Project project, RemoteHost.Remote remote) {
      StringBuilder html = new StringBuilder("<html><table cellpadding='1'>");
      for (String[] fact : remote.facts()) {
        html.append("<tr><td valign='top'>").append(escape(fact[0])).append(":</td><td>").append(escape(fact[1])).append("</td></tr>");
      }
      html.append("</table><br>Do you want to disconnect?</html>");
      boolean disconnect = MessageDialogBuilder.yesNo("Remote Computer", html.toString())
          .yesText("Disconnect")
          .noText("Keep the Connection")
          .icon(AllIcons.Webreferences.Server)
          .ask(project);
      if (disconnect && RemoteHost.connected(project) == remote) RemoteHost.getInstance(project).disconnect();
    }

    private static String escape(String s) {
      return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
  }

  /**
   * One fact about the NVIDIA GPU ({@link GpuInfo}, or the remote one): its name, VRAM or CUDA; hidden without
   * one. A click opens the System Monitor ({@link MonitorToolWindowFactory}).
   */
  private static final class GpuLabel extends Label {
    private final Project project;
    private final Icon icon;
    private final Function<GpuInfo.Gpu, String> text;

    GpuLabel(Project project, Icon icon, Function<GpuInfo.Gpu, String> text) {
      this.project = project;
      this.icon = icon;
      this.text = text;
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
      return clickable(super.createCustomComponent(presentation, place), () -> {
        if (!project.isDisposed()) MonitorToolWindowFactory.open(project);
      });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Presentation p = e.getPresentation();
      RemoteHost.Remote remote = RemoteHost.connected(e.getProject());
      GpuInfo.Gpu gpu = remote != null ? remote.gpu() : GpuInfo.get();
      String value = gpu == null ? null : text.apply(gpu);
      p.setVisible(value != null);
      if (value == null) return;
      p.setIcon(icon);
      p.setText(value, false);
      p.setDescription(gpu.tooltip() + ". Click for the System Monitor");
    }
  }

  /** Update / open JupyterLab / remove; the tooltip shows the exact command. */
  private static final class CommandButton extends AnAction implements DumbAware {
    private final CondaEnv.Command cmd;
    private final VirtualFile file;

    CommandButton(CondaEnv.Command cmd, VirtualFile file) {
      super(cmd.title, null, cmd.icon);
      this.cmd = cmd;
      this.file = file;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      String line = project == null ? null : cmd.describe(project, file);
      e.getPresentation().setEnabled(line != null);
      e.getPresentation().setDescription(line);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      if (project != null) cmd.run(project, file);
    }
  }

  /** Opens the environment's folder in the system file manager; disabled while the environment is not installed. */
  private static final class FolderButton extends AnAction implements DumbAware {
    private final VirtualFile file;

    FolderButton(VirtualFile file) {
      super("Open environment folder", null, AllIcons.Actions.MenuOpen);
      this.file = file;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }

    private @Nullable Path prefix() {
      String env = CondaEnv.envName(file);
      return env == null ? null : CondaEnvSizes.getInstance().status(env).prefix();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      // the file manager's name ("Files", "Explorer", "Finder") may be looked up on disk, so not on the EDT
      e.getPresentation().setText("Open environment folder in " + RevealFileAction.getFileManagerName());
      if (RemoteHost.connected(e.getProject()) != null) {
        e.getPresentation().setEnabled(false);
        e.getPresentation().setDescription("Only for environments on this computer");
        return;
      }
      Path prefix = prefix();
      e.getPresentation().setEnabled(prefix != null && RevealFileAction.isSupported());
      e.getPresentation().setDescription(prefix == null ? null : prefix.toString());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Path prefix = prefix();
      if (prefix != null) RevealFileAction.openDirectory(prefix);
    }
  }
}
