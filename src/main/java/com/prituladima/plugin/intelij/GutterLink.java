package com.prituladima.plugin.intelij;

import javax.swing.Icon;

/** A logo in the gutter of .conda_env.yml that opens a website when clicked. */
interface GutterLink {
  String title();

  String url();

  Icon icon();
}
