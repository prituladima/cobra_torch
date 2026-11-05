package com.prituladima.plugin.intellij;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * The System Monitor tool window ({@link MonitorPanel}). Hidden until first opened, from a click on the GPU
 * in a .conda_env.yml toolbar or from Tools | System Monitor, so it adds nothing to projects that never use it.
 */
public final class MonitorToolWindowFactory implements ToolWindowFactory, DumbAware {
  static final String ID = "CobraTorchMonitor";

  @Override
  public boolean shouldBeAvailable(@NotNull Project project) {
    return false;
  }

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    Content content = ContentFactory.getInstance().createContent(null, "", false);
    MonitorPanel panel = new MonitorPanel(project, content);
    content.setComponent(panel);
    Disposer.register(content, panel);
    toolWindow.getContentManager().addContent(content);
  }

  static void open(Project project) {
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ID);
    if (window == null) return;
    window.setAvailable(true);
    window.activate(null);
  }
}
