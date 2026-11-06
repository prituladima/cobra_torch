package com.prituladima.plugin.intellij;

import com.intellij.ui.JBColor;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.function.DoubleFunction;

/**
 * One measure over the last minute, the way the IDE's profiler draws it: time on X (a minute ago to now),
 * the full capacity on Y (100%, all of the RAM), a 2px line over a light wash of its color down to the
 * axis, hairline gridlines, and the name and current value as text above. Hovering shows the value at
 * that moment. {@code compact} is the small version used for each CPU core.
 */
final class AreaChart extends JComponent implements Meter {
  static final long WINDOW_MS = 60_000;
  private static final int CAPACITY = 128;   // a minute at one reading a second, with room to spare

  /** The categorical slots, in fixed order, each stepped for the light and the dark theme. */
  static final JBColor BLUE = new JBColor(new Color(0x2a78d6), new Color(0x3987e5));
  static final JBColor ORANGE = new JBColor(new Color(0xeb6834), new Color(0xd95926));
  static final JBColor AQUA = new JBColor(new Color(0x1baf7a), new Color(0x199e70));
  static final JBColor YELLOW = new JBColor(new Color(0xeda100), new Color(0xc98500));
  static final JBColor MAGENTA = new JBColor(new Color(0xe87ba4), new Color(0xd55181));

  private static final JBColor GRID = new JBColor(new Color(0xE4E5E8), new Color(0x393B40));
  private static final JBColor BASELINE = new JBColor(new Color(0xC9CCD6), new Color(0x4E5157));

  private final String name;
  private final Color color;
  private final DoubleFunction<String> format;
  private final boolean compact;
  private final long[] times = new long[CAPACITY];
  private final double[] values = new double[CAPACITY];
  private int size;
  private double max = 1;
  private @Nullable String valueText;   // the current value, as the title shows it, e.g. "9.8 of 15.5 GB"
  private int hoverX = -1;

  AreaChart(String name, Color color, DoubleFunction<String> format, boolean compact) {
    this.name = name;
    this.color = color;
    this.format = format;
    this.compact = compact;
    setOpaque(false);
    MouseAdapter hover = new MouseAdapter() {
      @Override
      public void mouseMoved(MouseEvent e) {
        hoverX = e.getX();
        repaint();
      }

      @Override
      public void mouseExited(MouseEvent e) {
        hoverX = -1;
        repaint();
      }
    };
    addMouseListener(hover);
    addMouseMotionListener(hover);
  }

  /** A reading: the value, the full scale it is drawn against, and the title's text for it. */
  @Override
  public void add(long time, double value, double fullScale, String text) {
    if (size == CAPACITY) {
      System.arraycopy(times, 1, times, 0, CAPACITY - 1);
      System.arraycopy(values, 1, values, 0, CAPACITY - 1);
      size--;
    }
    times[size] = time;
    values[size] = value;
    size++;
    max = fullScale > 0 ? fullScale : 1;
    valueText = text;
    repaint();
  }

  @Override
  public Dimension getPreferredSize() {
    return preferredSize(compact);
  }

  @Override
  public Dimension getMinimumSize() {
    return minimumSize(compact);
  }

  /** The chart's size, which {@link BarMeter} takes too, so a bar stands where a graph would. */
  static Dimension preferredSize(boolean compact) {
    return new Dimension(JBUI.scale(compact ? 120 : 220), JBUI.scale(compact ? 58 : 112));
  }

  static Dimension minimumSize(boolean compact) {
    return new Dimension(JBUI.scale(compact ? 60 : 120), JBUI.scale(compact ? 48 : 90));
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      Font titleFont = compact ? JBFont.small() : JBFont.label();
      Font axisFont = JBFont.small();
      FontMetrics tm = g2.getFontMetrics(titleFont);
      FontMetrics am = g2.getFontMetrics(axisFont);
      int pad = JBUI.scale(4);
      int top = tm.getHeight() + pad;
      int axisH = compact ? 0 : am.getHeight();
      int x0 = pad, x1 = getWidth() - pad;
      int y0 = top, y1 = getHeight() - pad - axisH;
      int w = x1 - x0, h = y1 - y0;
      if (w <= 0 || h <= 0) return;

      // gridlines: full scale and half; the axis
      g2.setStroke(new BasicStroke(1f));
      g2.setColor(GRID);
      g2.drawLine(x0, y0, x1, y0);
      g2.drawLine(x0, y0 + h / 2, x1, y0 + h / 2);
      g2.setColor(BASELINE);
      g2.drawLine(x0, y1, x1, y1);

      long now = size == 0 ? System.currentTimeMillis() : times[size - 1];
      long from = now - WINDOW_MS;
      int hovered = -1;
      if (size > 0) {
        Path2D line = new Path2D.Double();
        Path2D area = new Path2D.Double();
        boolean started = false;
        double firstX = 0, lastX = 0;
        for (int i = 0; i < size; i++) {
          if (times[i] < from - 2_000) continue;   // one point left of the edge keeps the line running into it
          double x = x0 + w * (double) (times[i] - from) / WINDOW_MS;
          double y = y1 - h * Math.max(0, Math.min(1, values[i] / max));
          if (!started) {
            line.moveTo(x, y);
            area.moveTo(x, y1);
            area.lineTo(x, y);
            firstX = x;
            started = true;
          } else {
            line.lineTo(x, y);
            area.lineTo(x, y);
          }
          lastX = x;
          if (hoverX >= 0 && (hovered < 0 || Math.abs(x - hoverX) < Math.abs(x0 + w * (double) (times[hovered] - from) / WINDOW_MS - hoverX))) {
            hovered = i;
          }
        }
        if (started) {
          area.lineTo(lastX, y1);
          area.lineTo(firstX, y1);
          area.closePath();
          Graphics2D clip = (Graphics2D) g2.create();
          clip.clipRect(x0, y0 - JBUI.scale(2), w, h + JBUI.scale(3));
          clip.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 46));   // a wash, ~18%
          clip.fill(area);
          clip.setColor(color);
          clip.setStroke(new BasicStroke(JBUIScale.scale(compact ? 1.5f : 2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
          clip.draw(line);
          clip.dispose();
        }
      }

      // hover: a hairline at that moment and a dot with a ring in the surface color
      String shown = valueText;
      if (hovered >= 0) {
        double hx = x0 + w * (double) (times[hovered] - from) / WINDOW_MS;
        double hy = y1 - h * Math.max(0, Math.min(1, values[hovered] / max));
        g2.setColor(BASELINE);
        g2.drawLine((int) hx, y0, (int) hx, y1);
        double r = JBUI.scale(4), ring = JBUI.scale(2);
        g2.setColor(UIUtil.getPanelBackground());
        g2.fill(new Ellipse2D.Double(hx - r - ring, hy - r - ring, 2 * (r + ring), 2 * (r + ring)));
        g2.setColor(color);
        g2.fill(new Ellipse2D.Double(hx - r, hy - r, 2 * r, 2 * r));
        long ago = Math.round((now - times[hovered]) / 1000.0);
        shown = format.apply(values[hovered]) + (ago == 0 ? ", now" : ", " + ago + " s ago");
      }

      // title row: the name in secondary ink, the value in primary ink; the full scale at the right, out of the data's way
      int baseline = pad + tm.getAscent();
      String full = compact ? "" : format.apply(max);
      g2.setFont(axisFont);
      int fullW = full.isEmpty() ? 0 : am.stringWidth(full) + JBUI.scale(8);
      if (!full.isEmpty()) {
        g2.setColor(UIUtil.getContextHelpForeground());
        g2.drawString(full, x1 - am.stringWidth(full), baseline);
      }
      g2.setFont(titleFont);
      g2.setColor(UIUtil.getContextHelpForeground());
      g2.drawString(name, x0, baseline);
      if (shown != null) {
        g2.setColor(UIUtil.getLabelForeground());
        int nameW = tm.stringWidth(name + "  ");
        String text = shown;
        int room = w - nameW - fullW;
        if (tm.stringWidth(text) > room) {   // too narrow: cut, and say so
          while (text.length() > 1 && tm.stringWidth(text + "…") > room) text = text.substring(0, text.length() - 1);
          text = text.strip() + "…";
        }
        g2.drawString(text, x0 + nameW, baseline);
      }

      // the time span below
      if (!compact) {
        g2.setFont(axisFont);
        g2.setColor(UIUtil.getContextHelpForeground());
        g2.drawString("1 min ago", x0, y1 + am.getAscent());
        g2.drawString("now", x1 - am.stringWidth("now"), y1 + am.getAscent());
      }
    } finally {
      g2.dispose();
    }
  }
}
