package com.prituladima.plugin.intellij;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

/**
 * One measure as it is now, the {@link AreaChart}'s other form: the name and value as text, as in the chart's
 * title row, and below them a thin bar filled to the value's share of the full scale, over a track.
 */
final class BarMeter extends JComponent implements Meter {
  private static final JBColor TRACK = new JBColor(new Color(0xE4E5E8), new Color(0x393B40));

  private final String name;
  private final Color color;
  private final boolean compact;
  private double share;
  private @Nullable String valueText;

  BarMeter(String name, Color color, boolean compact) {
    this.name = name;
    this.color = color;
    this.compact = compact;
    setOpaque(false);
  }

  @Override
  public void add(long time, double value, double fullScale, String text) {
    share = fullScale > 0 ? Math.max(0, Math.min(1, value / fullScale)) : 0;
    valueText = text;
    repaint();
  }

  @Override
  public Dimension getPreferredSize() {
    FontMetrics fm = getFontMetrics(font());
    return new Dimension(JBUI.scale(compact ? 60 : 160), fm.getHeight() + JBUI.scale(compact ? 14 : 16));
  }

  @Override
  public Dimension getMinimumSize() {
    return new Dimension(JBUI.scale(40), getPreferredSize().height);
  }

  private Font font() {
    return compact ? JBFont.small() : JBFont.label();
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      Font font = font();
      FontMetrics fm = g2.getFontMetrics(font);
      int pad = JBUI.scale(4), barH = JBUI.scale(compact ? 5 : 6);
      int x0 = pad, w = getWidth() - 2 * pad;
      if (w <= 0) return;

      // name in secondary ink, value in primary ink, cut with "…" when too narrow
      int baseline = pad + fm.getAscent();
      g2.setFont(font);
      g2.setColor(UIUtil.getContextHelpForeground());
      g2.drawString(name, x0, baseline);
      if (valueText != null) {
        int nameW = fm.stringWidth(name + "  ");
        String text = valueText;
        int room = w - nameW;
        if (fm.stringWidth(text) > room) {
          while (text.length() > 1 && fm.stringWidth(text + "…") > room) text = text.substring(0, text.length() - 1);
          text = text.strip() + "…";
        }
        g2.setColor(UIUtil.getLabelForeground());
        g2.drawString(text, x0 + nameW, baseline);
      }

      // the track, and the bar from the left with rounded ends
      double y = pad + fm.getHeight() + JBUI.scale(3), arc = barH;
      g2.setColor(TRACK);
      g2.fill(new RoundRectangle2D.Double(x0, y, w, barH, arc, arc));
      double fill = w * share;
      if (fill > 0) {
        g2.setColor(color);
        g2.fill(new RoundRectangle2D.Double(x0, y, Math.max(fill, barH), barH, arc, arc));
      }
    } finally {
      g2.dispose();
    }
  }
}
