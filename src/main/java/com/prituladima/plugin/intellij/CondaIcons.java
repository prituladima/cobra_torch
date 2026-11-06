package com.prituladima.plugin.intellij;

import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * The plugin's own icons, other than the package logos ({@link KnownPackage}). The green Anaconda ring
 * (icons/conda/anaconda.svg and its _dark variant) is JetBrains' own drawing from intellij-community,
 * Apache 2.0: the IDE's copy is internal API and moved between releases, so the plugin carries its own.
 * The NVIDIA, Intel, AMD and disk makers' marks identify the hardware; their sources are in LOGO_SOURCES.md.
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

  /**
   * The maker's mark for a disk, from its model as the drive reports it: {@code Samsung SSD 990 PRO 2TB}, but
   * also the makers' short codes ({@code ST4000DM004} Seagate, {@code CT1000P3SSD8} Crucial, {@code WDC WD40EFRX}
   * Western Digital, {@code MZVL21T0HCLR} Samsung OEM); null for virtual and unknown disks.
   */
  static @Nullable Icon disk(String model) {
    DiskMaker maker = DiskMaker.of(model);
    return maker == null ? null : maker.icon();
  }

  /** Disk makers with a logo, each with the model prefixes their drives report. */
  enum DiskMaker {
    SAMSUNG("samsung", "(?i)^(samsung|mz[0-9a-z]{2,})"),
    WESTERN_DIGITAL("western-digital", "(?i)^(wdc|wd[ _]|wd\\d|western digital|wd_black)"),
    SANDISK("sandisk", "(?i)^sandisk"),
    SEAGATE("seagate", "(?i)^(seagate|st\\d{3,})"),
    KINGSTON("kingston", "(?i)^kingston"),
    CRUCIAL("crucial", "(?i)^(crucial|ct\\d{3,})"),
    MICRON("micron", "(?i)^(micron|mtfd)"),
    SK_HYNIX("sk-hynix", "(?i)^(sk ?hynix|hfs\\d|hfm\\d)"),
    KIOXIA("kioxia", "(?i)^(kioxia|kxg|kbg|kcd|kcm)"),
    TOSHIBA("toshiba", "(?i)^toshiba"),
    INTEL("intel", "(?i)^(intel|ssdpe|ssdsc)");

    private final String file;
    private final java.util.regex.Pattern model;

    DiskMaker(String file, String model) {
      this.file = file;
      this.model = java.util.regex.Pattern.compile(model);
    }

    static @Nullable DiskMaker of(String model) {
      for (DiskMaker m : values()) if (m.model.matcher(model.trim()).find()) return m;
      return null;
    }

    @Nullable Icon icon() {
      if (this == INTEL) return CondaIcons.INTEL;
      return CondaIcons.class.getResource("/icons/disk/" + file + ".png") == null ? null
          : IconLoader.getIcon("/icons/disk/" + file + ".png", CondaIcons.class);
    }
  }
}
