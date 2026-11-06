package com.prituladima.plugin.intellij;

import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * The plugin's own icons, other than the package logos ({@link KnownPackage}). The green Anaconda ring
 * (icons/conda/anaconda.svg and its _dark variant) is JetBrains' own drawing from intellij-community,
 * Apache 2.0: the IDE's copy is internal API and moved between releases, so the plugin carries its own.
 * The NVIDIA, Intel and AMD marks identify the hardware; their sources are in LOGO_SOURCES.md.
 */
final class CondaIcons {
  static final Icon CONDA = IconLoader.getIcon("/icons/conda/anaconda.svg", CondaIcons.class);
  static final Icon NVIDIA = IconLoader.getIcon("/icons/toolbar/nvidia.png", CondaIcons.class);
  static final Icon INTEL = IconLoader.getIcon("/icons/cpu/intel.png", CondaIcons.class);
  static final Icon AMD = IconLoader.getIcon("/icons/cpu/amd.svg", CondaIcons.class);

  private CondaIcons() {}

  /** The maker's mark for a CPU, from its vendor ({@code GenuineIntel}, {@code AuthenticAMD}) or name; null for others. */
  static @Nullable Icon cpu(@Nullable String vendor, @Nullable String name) {
    String s = (vendor == null ? "" : vendor) + " " + (name == null ? "" : name);
    if (s.contains("Intel")) return INTEL;
    if (s.contains("AMD")) return AMD;
    return null;
  }
}
