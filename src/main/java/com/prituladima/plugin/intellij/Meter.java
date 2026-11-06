package com.prituladima.plugin.intellij;

/** A measure the System Monitor feeds once a second: a graph of the last minute ({@link AreaChart}) or a bar of now ({@link BarMeter}). */
interface Meter {
  /** A reading: the value, the full scale it is drawn against, and the text that tells it. */
  void add(long time, double value, double fullScale, String text);
}
