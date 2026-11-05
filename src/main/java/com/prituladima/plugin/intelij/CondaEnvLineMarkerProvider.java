package com.prituladima.plugin.intelij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gutter of .conda_env.yml, at most one icon per line (the leftmost match wins):
 * <ul>
 *   <li>the "update" button on line 1 and the "remove" button on line 2, anchored to the leaf that holds
 *       the line's first character (which may be whitespace, so a blank line 2 still gets its button);</li>
 *   <li>a logo on every {@code dependencies:} item that is a {@link KnownPackage} and every
 *       {@code channels:} item that is a {@link KnownChannel}; a click opens its website.</li>
 * </ul>
 * Everything comes from {@link #getLineMarkerInfo}, one marker per leaf, so a marker is never added twice.
 */
public final class CondaEnvLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware {

  /** {@code - name}, {@code - name=1.2}, {@code - "name>=1"}, {@code - channel::name}; group 1 is the name. */
  private static final Pattern ITEM = Pattern.compile("[ \\t]*-[ \\t]+['\"]?(?:[\\w.-]+::)?([\\w.-]+)");
  private static final Pattern TOP_LEVEL_KEY = Pattern.compile("([\\w.-]+)[ \\t]*:");

  /** One gutter icon: where it sits, and either a conda command or a website link. */
  record Slot(int start, int end, @Nullable CondaEnv.Command command, @Nullable GutterLink link) {}

  /** A dependency or channel name found in the file. */
  record Hit(GutterLink link, int end) {}

  @Override
  public @NotNull String getName() {
    return "Conda env file: update/remove buttons and package logos";
  }

  @Override
  public @Nullable Icon getIcon() {
    return CondaIcons.CONDA;
  }

  @Override
  public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
    if (element.getFirstChild() != null) return null;
    PsiFile file = element.getContainingFile();
    if (file == null || !CondaEnv.isCondaEnvFile(file.getViewProvider().getVirtualFile())) return null;
    Document doc = file.getViewProvider().getDocument();
    if (doc == null) return null;

    TextRange r = element.getTextRange();
    Map.Entry<Integer, Slot> first = slots(doc).ceilingEntry(r.getStartOffset());
    if (first == null || first.getKey() >= r.getEndOffset()) return null;
    Slot slot = first.getValue();
    TextRange range = new TextRange(slot.start(), Math.min(r.getEndOffset(), slot.end()));

    if (slot.command() != null) {
      CondaEnv.Command cmd = slot.command();
      return new LineMarkerInfo<>(element, range, cmd.icon,
          e -> cmd.tooltip(e),
          (mouseEvent, e) -> cmd.run(e),
          GutterIconRenderer.Alignment.CENTER,
          () -> cmd.title);
    }
    GutterLink link = slot.link();
    return new LineMarkerInfo<>(element, range, link.icon(),
        e -> link.title() + ": " + link.url(),
        (mouseEvent, e) -> BrowserUtil.browse(link.url()),
        GutterIconRenderer.Alignment.CENTER,
        link::title);
  }

  /** Start offset -> the single icon of its line: the leftmost of the line's command and link. */
  static NavigableMap<Integer, Slot> slots(Document doc) {
    Map<Integer, Slot> byLine = new TreeMap<>();
    for (Map.Entry<Integer, Hit> hit : links(doc).entrySet()) {
      int start = hit.getKey();
      byLine.put(doc.getLineNumber(start), new Slot(start, hit.getValue().end(), null, hit.getValue().link()));
    }
    for (CondaEnv.Command cmd : CondaEnv.Command.values()) {
      if (cmd.line >= doc.getLineCount()) continue;
      int start = doc.getLineStartOffset(cmd.line);
      int end = Math.max(doc.getLineEndOffset(cmd.line), start + 1);
      Slot existing = byLine.get(cmd.line);
      if (existing == null || start <= existing.start()) byLine.put(cmd.line, new Slot(start, end, cmd, null));
    }
    NavigableMap<Integer, Slot> byOffset = new TreeMap<>();
    for (Slot s : byLine.values()) byOffset.put(s.start(), s);
    return byOffset;
  }

  /** Name offset -> link, for known items under the top-level {@code dependencies:} and {@code channels:} keys. */
  static NavigableMap<Integer, Hit> links(Document doc) {
    NavigableMap<Integer, Hit> hits = new TreeMap<>();
    CharSequence text = doc.getCharsSequence();
    String section = "";
    for (int line = 0; line < doc.getLineCount(); line++) {
      int start = doc.getLineStartOffset(line);
      CharSequence s = text.subSequence(start, doc.getLineEndOffset(line));
      if (s.length() > 0 && " \t#-".indexOf(s.charAt(0)) < 0) {   // a top-level key starts a new section
        Matcher key = TOP_LEVEL_KEY.matcher(s);
        section = key.lookingAt() ? key.group(1) : "";
        continue;
      }
      Matcher m = ITEM.matcher(s);
      if (!m.lookingAt()) continue;
      GutterLink link = switch (section) {
        case "dependencies" -> KnownPackage.byName(m.group(1));
        case "channels" -> KnownChannel.byName(m.group(1));
        default -> null;
      };
      if (link != null) hits.put(start + m.start(1), new Hit(link, start + m.end(1)));
    }
    return hits;
  }
}
