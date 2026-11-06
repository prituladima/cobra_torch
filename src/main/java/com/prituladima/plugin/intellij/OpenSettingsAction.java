package com.prituladima.plugin.intellij;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Tools | Cobra torch settings, and the gear of the System Monitor: opens Settings | Tools | Cobra torch. */
public final class OpenSettingsAction extends AnAction implements DumbAware {
  static final String ID = "com.prituladima.cobratorch.Settings";

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), CobraTorchConfigurable.class);
  }
}
