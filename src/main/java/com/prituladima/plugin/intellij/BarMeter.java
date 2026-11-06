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
import java.util.function.DoubleFunction;

/**
 * One measure as it is now, the {@link AreaChart}'s other form, the way a task manager draws CPU usage:
 * a vertical green meter of small cells, lit from the bottom up to the value's share of the full scale,
 * the cells above it dim. It takes the chart's place exactly: the same size, the same title row (name,
 * value, full scale) and the same plot box, with "now" below where the chart has its time span.
 */
final class BarMeter extends JComponent implements Meter {
  /** The task-manager green, stepped for the light and the dark theme. */
  static final JBColor GREEN = new JBColor(new Color(0x1f9e3a), new Color(0x3fbf56));

  private final String name;
  private final DoubleFunction<String> format;
  private final boolean compact;
  private double share;
  private double full = 1;
  private @Nullable String valueText;

  BarMeter(String name, DoubleFunction<String> format, boolean compact) {
    this.name = name;
    this.format = format;
    this.compact = compact;
    setOpaque(false);
  }

  @Override
  public void add(long time, double value, double fullScale, String text) {
    full = fullScale > 0 ? fullScale : 1;
    share = Math.max(0, Math.min(1, value / full));
    valueText = text;
    repaint();
  }

  @Override
  public Dimension getPreferredSize() {
    return AreaChart.preferredSize(compact);
  }

  @Override
  public Dimension getMinimumSize() {
    return AreaChart.minimumSize(compact);
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      Font titleFont = compact ? JBFont.small() : JBFont.label();
      Font axisFont = JBFont.small();
      FontMetrics tm = g2.getFontMetrics(titleFont);
      FontMetrics am = g2.getFontMetrics(axisFont);
      // the chart's plot box, to the pixel
      int pad = JBUI.scale(4);
      int top = tm.getHeight() + pad;
      int axisH = compact ? 0 : am.getHeight();
      int x0 = pad, x1 = getWidth() - pad;
      int y0 = top, y1 = getHeight() - pad - axisH;
      int w = x1 - x0, h = y1 - y0;
      if (w <= 0 || h <= 0) return;

      // cells of the full width with a gap between, as many as fit, lit from the bottom
      int cell = JBUI.scale(compact ? 2 : 3), gap = JBUI.scale(1), pitch = cell + gap;
      int count = Math.max(1, (h + gap) / pitch);
      int lit = (int) Math.round(share * count);
      int bottom = y1 - (h + gap - count * pitch) / 2;   // the spare pixels split above and below
      Color on = GREEN;
      Color off = new Color(on.getRed(), on.getGreen(), on.getBlue(), 40);   // a dim cell, ~16%
      for (int i = 0; i < count; i++) {
        g2.setColor(i < lit ? on : off);
        g2.fillRect(x0, bottom - (i + 1) * pitch + gap, w, cell);
      }

      // title row as in the chart: name in secondary ink, value in primary ink, the full scale at the right
      int baseline = pad + tm.getAscent();
      String fullText = compact ? "" : format.apply(full);
      int fullW = fullText.isEmpty() ? 0 : am.stringWidth(fullText) + JBUI.scale(8);
      if (!fullText.isEmpty()) {
        g2.setFont(axisFont);
        g2.setColor(UIUtil.getContextHelpForeground());
        g2.drawString(fullText, x1 - am.stringWidth(fullText), baseline);
      }
      g2.setFont(titleFont);
      g2.setColor(UIUtil.getContextHelpForeground());
      g2.drawString(name, x0, baseline);
      if (valueText != null) {
        int nameW = tm.stringWidth(name + "  ");
        String text = valueText;
        int room = w - nameW - fullW;
        if (tm.stringWidth(text) > room) {   // too narrow: cut, and say so
          while (text.length() > 1 && tm.stringWidth(text + "…") > room) text = text.substring(0, text.length() - 1);
          text = text.strip() + "…";
        }
        g2.setColor(UIUtil.getLabelForeground());
        g2.drawString(text, x0 + nameW, baseline);
      }

      // where the chart says "1 min ago … now", the bar says it is now
      if (!compact) {
        g2.setFont(axisFont);
        g2.setColor(UIUtil.getContextHelpForeground());
        g2.drawString("now", x1 - am.stringWidth("now"), y1 + am.getAscent());
      }
    } finally {
      g2.dispose();
    }
  }
}
