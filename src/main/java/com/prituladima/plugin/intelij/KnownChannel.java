package com.prituladima.plugin.intelij;

import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Conda channels under {@code channels:} that get the conda icon in the gutter. */
enum KnownChannel implements GutterLink {
  CONDA_FORGE("conda-forge", "https://conda-forge.org");

  private final String title;
  private final String url;

  KnownChannel(String title, String url) {
    this.title = title;
    this.url = url;
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  public String url() {
    return url;
  }

  @Override
  public Icon icon() {
    return CondaIcons.CONDA;
  }

  static @Nullable KnownChannel byName(String name) {
    for (KnownChannel c : values()) {
      if (c.title.equalsIgnoreCase(name)) return c;
    }
    return null;
  }
}
