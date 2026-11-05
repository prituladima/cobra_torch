package com.prituladima.plugin.intellij;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Registered by META-INF/conda-python.xml, i.e. only when the Python plugin (PythonCore) is enabled. */
public final class CondaFileIconProvider implements FileIconProvider {

  @Override
  public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
    return CondaEnv.isCondaEnvFile(file) ? CondaIcons.CONDA : null;
  }
}
