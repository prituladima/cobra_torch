package com.prituladima.plugin.intellij;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.ide.ActivityTracker;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Redraws what the plugin shows for .conda_env.yml files after something changed outside the editor. */
final class CondaEnvRefresh {
  private CondaEnvRefresh() {}

  /** The editor toolbars: their actions are updated again. */
  static void toolbars() {
    ActivityTracker.getInstance().inc();
  }

  /** The version hints of the open .conda_env.yml files, of one project or (null) of all open projects. */
  static void hints(@Nullable Project only) {
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      List<Project> projects = only != null ? List.of(only) : List.of(ProjectManager.getInstance().getOpenProjects());
      for (Project project : projects) {
        ReadAction.runBlocking(() -> {
          if (project.isDisposed()) return;
          for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (!CondaEnv.isCondaEnvFile(file)) continue;
            PsiFile psi = PsiManager.getInstance(project).findFile(file);
            if (psi != null) DaemonCodeAnalyzer.getInstance(project).restart(psi, "Cobra torch: installed packages changed");
          }
        });
      }
    });
  }
}
