package com.prituladima.plugin.intellij;

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

/**
 * Gutter of .conda_env.yml: a logo on every {@code dependencies:} item that is a {@link KnownPackage} and
 * every {@code channels:} item that is a {@link KnownChannel}; a click opens its website. At most one icon
 * per line, from {@link #getLineMarkerInfo}, one marker per leaf, so a marker is never added twice.
 * The conda commands are on the editor's toolbar ({@link CondaEnvToolbar}).
 */
public final class CondaEnvLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware {

  /** A dependency or channel name found in the file. */
  record Hit(GutterLink link, int end) {}

  @Override
  public @NotNull String getName() {
    return "Conda env file: package logos";
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
    Map.Entry<Integer, Hit> first = links(doc).ceilingEntry(r.getStartOffset());
    if (first == null || first.getKey() >= r.getEndOffset()) return null;
    GutterLink link = first.getValue().link();
    TextRange range = new TextRange(first.getKey(), Math.min(r.getEndOffset(), first.getValue().end()));
    return new LineMarkerInfo<>(element, range, link.icon(),
        e -> link.title() + ": " + link.url(),
        (mouseEvent, e) -> BrowserUtil.browse(link.url()),
        GutterIconRenderer.Alignment.CENTER,
        link::title);
  }

  /** Name offset -> link, for known items under the top-level {@code dependencies:} and {@code channels:} keys. */
  static NavigableMap<Integer, Hit> links(Document doc) {
    NavigableMap<Integer, Hit> hits = new TreeMap<>();
    for (EnvFileItems.Item item : EnvFileItems.parse(doc)) {
      GutterLink link = switch (item.section()) {
        case "dependencies" -> KnownPackage.byName(item.name());
        case "channels" -> KnownChannel.byName(item.name());
        default -> null;
      };
      if (link != null) hits.put(item.nameStart(), new Hit(link, item.nameEnd()));
    }
    return hits;
  }
}
