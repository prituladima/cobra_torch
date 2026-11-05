package com.prituladima.plugin.intelij;

import com.intellij.icons.AllIcons;
import com.jetbrains.python.icons.PythonIcons;

import javax.swing.Icon;

/** The conda icon of the Python plugin (public API); the YAML icon when the Python plugin is absent. */
final class CondaIcons {
  static final Icon CONDA = load();

  private CondaIcons() {}

  private static Icon load() {
    try {
      return PythonIcons.Python.Condaenv;   // Python logo with a "C" badge
    } catch (LinkageError ignored) {
      return AllIcons.FileTypes.Yaml;
    }
  }
}
