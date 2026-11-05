package com.prituladima.plugin.intellij;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/**
 * The green Anaconda ring, bundled with the plugin (icons/conda/anaconda.svg and its _dark variant):
 * JetBrains' own drawing from intellij-community, Apache 2.0. The IDE's copy is internal API and moved
 * between releases, so the plugin carries its own.
 */
final class CondaIcons {
  static final Icon CONDA = IconLoader.getIcon("/icons/conda/anaconda.svg", CondaIcons.class);

  private CondaIcons() {}
}
