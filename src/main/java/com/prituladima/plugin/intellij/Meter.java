package com.prituladima.plugin.intellij;

import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import java.awt.FontMetrics;
import java.awt.Graphics2D;

/** A measure the System Monitor feeds once a second: a graph of the last minute ({@link AreaChart}) or a bar of now ({@link BarMeter}). */
interface Meter {
  /** A reading: the value, the full scale it is drawn against, and the text that tells it. */
  void add(long time, double value, double fullScale, String text);

  /** The name to show when the full one leaves no room for the value: "12" for "CPU 12". */
  void setShortName(String shortName);

  /**
   * The title row of a meter: its name in secondary ink, then the value in primary ink, within {@code room}.
   * The first that fits of "CPU 12  64%", "12  64%", "12 64%", "64%" (the meter's place still tells which it
   * is); with no room even for the value, the name and the value cut with "…".
   */
  static void title(Graphics2D g, FontMetrics fm, String name, @Nullable String shortName, @Nullable String value, int x,
                    int baseline, int room) {
    String n = name, gap = "  ";
    if (value != null && fm.stringWidth(name + gap + value) > room) {
      String s = shortName == null ? name : shortName;
      if (fm.stringWidth(s + gap + value) <= room) n = s;
      else if (fm.stringWidth(s + " " + value) <= room) {
        n = s;
        gap = " ";
      } else if (fm.stringWidth(value) <= room) {
        n = "";
        gap = "";
      }
    }
    if (!n.isEmpty()) {
      g.setColor(UIUtil.getContextHelpForeground());
      g.drawString(n, x, baseline);
    }
    if (value == null) return;
    int nameW = n.isEmpty() ? 0 : fm.stringWidth(n + gap);
    int left = room - nameW;
    String text = value;
    if (fm.stringWidth(text) > left) {   // too narrow: cut, and say so
      while (text.length() > 1 && fm.stringWidth(text + "…") > left) text = text.substring(0, text.length() - 1);
      text = text.strip() + "…";
    }
    g.setColor(UIUtil.getLabelForeground());
    g.drawString(text, x + nameW, baseline);
  }
}
