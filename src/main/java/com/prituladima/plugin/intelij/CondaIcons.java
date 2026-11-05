package com.prituladima.plugin.intelij;

import com.intellij.icons.AllIcons;
import com.intellij.python.community.impl.conda.icons.PythonCommunityImplCondaIcons;
import com.jetbrains.python.icons.PythonIcons;

import javax.swing.Icon;

/** The conda icons ship with the Python plugin; each step falls back if a class is missing. */
final class CondaIcons {
  static final Icon CONDA = load();

  private CondaIcons() {}

  private static Icon load() {
    try {
      return PythonCommunityImplCondaIcons.Anaconda;   // green Anaconda ring
    } catch (LinkageError ignored) {
    }
    try {
      return PythonIcons.Python.Condaenv;             // Python logo with a "C" badge
    } catch (LinkageError ignored) {
    }
    return AllIcons.FileTypes.Yaml;                   // Python plugin absent
  }
}
