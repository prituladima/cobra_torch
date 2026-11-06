package com.prituladima.plugin.intellij;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Children side by side, each as wide as its weight's share of the row, whatever they would prefer: a graph of
 * weight 3 next to a bar of weight 1 takes three quarters. A hidden child takes no room, so the others share it;
 * the row is as tall as its tallest child. The weight is the constraint of {@code add(child, weight)}, 1 if none.
 */
final class WeightRow implements LayoutManager2 {
  private final int gap;
  private final Map<Component, Double> weights = new HashMap<>();

  WeightRow(int gap) {
    this.gap = gap;
  }

  @Override
  public void addLayoutComponent(Component c, Object constraints) {
    weights.put(c, constraints instanceof Number n ? n.doubleValue() : 1.0);
  }

  @Override
  public void addLayoutComponent(String name, Component c) {
    weights.put(c, 1.0);
  }

  @Override
  public void removeLayoutComponent(Component c) {
    weights.remove(c);
  }

  private static List<Component> visible(Container parent) {
    List<Component> list = new ArrayList<>();
    for (Component c : parent.getComponents()) if (c.isVisible()) list.add(c);
    return list;
  }

  @Override
  public void layoutContainer(Container parent) {
    Insets in = parent.getInsets();
    List<Component> shown = visible(parent);
    if (shown.isEmpty()) return;
    int width = parent.getWidth() - in.left - in.right - gap * (shown.size() - 1);
    int height = parent.getHeight() - in.top - in.bottom;
    double total = 0;
    for (Component c : shown) total += weights.getOrDefault(c, 1.0);
    double sum = 0;
    int x = in.left;
    for (int i = 0; i < shown.size(); i++) {
      Component c = shown.get(i);
      sum += weights.getOrDefault(c, 1.0);
      // each right edge from the running total, so that rounding errors do not pile up
      int right = in.left + (int) Math.round(width * sum / total) + gap * i;
      c.setBounds(x, in.top, Math.max(0, right - x), height);
      x = right + gap;
    }
  }

  @Override
  public Dimension preferredLayoutSize(Container parent) {
    return size(parent, false);
  }

  /** Small: a row narrower than it would like still fits, its children cut their text with "…". */
  @Override
  public Dimension minimumLayoutSize(Container parent) {
    return size(parent, true);
  }

  private Dimension size(Container parent, boolean minimum) {
    Insets in = parent.getInsets();
    List<Component> shown = visible(parent);
    int w = 0, h = 0;
    for (Component c : shown) {
      Dimension d = c.getPreferredSize();
      w += minimum ? Math.min(d.width, 20) : d.width;
      h = Math.max(h, d.height);
    }
    return new Dimension(w + gap * Math.max(0, shown.size() - 1) + in.left + in.right, h + in.top + in.bottom);
  }

  @Override
  public Dimension maximumLayoutSize(Container target) {
    return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
  }

  @Override
  public float getLayoutAlignmentX(Container target) {
    return 0;
  }

  @Override
  public float getLayoutAlignmentY(Container target) {
    return 0;
  }

  @Override
  public void invalidateLayout(Container target) {
  }
}
