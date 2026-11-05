package com.prituladima.plugin.intelij;

import com.intellij.application.options.CodeStyle;
import com.intellij.ide.IdeView;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * File | New | Conda Env File: creates .conda_env.yml in the chosen directory from
 * resources/templates/conda_env.yml, with ${ENV_NAME} set to the directory name,
 * or opens the existing one.
 */
public final class NewCondaEnvFileAction extends AnAction implements DumbAware {

  private static final String TEMPLATE = "/templates/conda_env.yml";

  public NewCondaEnvFileAction() {
    getTemplatePresentation().setIcon(CondaIcons.CONDA);
  }

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    IdeView view = e.getData(LangDataKeys.IDE_VIEW);
    e.getPresentation().setEnabledAndVisible(
        e.getProject() != null && view != null && view.getDirectories().length > 0);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    IdeView view = e.getData(LangDataKeys.IDE_VIEW);
    if (project == null || view == null) return;
    PsiDirectory dir = view.getOrChooseDirectory();
    if (dir == null) return;

    VirtualFile folder = dir.getVirtualFile();
    VirtualFile file = folder.findChild(CondaEnv.FILE_NAME);
    if (file == null) {
      // the IDE's line-separator setting for new files (CRLF on Windows by default)
      String separator = CodeStyle.getProjectOrDefaultSettings(project).getLineSeparator();
      String text = StringUtil.convertLineSeparators(template().replace("${ENV_NAME}", folder.getName()), separator);
      try {
        file = WriteCommandAction.writeCommandAction(project)
            .withName("Create " + CondaEnv.FILE_NAME)
            .compute(() -> {
              VirtualFile created = folder.createChildData(NewCondaEnvFileAction.this, CondaEnv.FILE_NAME);
              VfsUtil.saveText(created, text);
              return created;
            });
      } catch (IOException ex) {
        Messages.showErrorDialog(project, ex.getMessage(), "Cannot Create " + CondaEnv.FILE_NAME);
        return;
      }
    }

    VirtualFile target = file;
    FileEditorManager.getInstance(project).openFile(target, true);
    PsiFile psi = ReadAction.computeBlocking(() -> PsiManager.getInstance(project).findFile(target));
    if (psi != null) view.selectElement(psi);
  }

  private static String template() {
    try (InputStream in = NewCondaEnvFileAction.class.getResourceAsStream(TEMPLATE)) {
      if (in == null) throw new IllegalStateException("missing resource " + TEMPLATE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
