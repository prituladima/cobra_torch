package com.prituladima.plugin.intelij;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.ide.BrowserUtil;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The official logo next to {@code import numpy} / {@code from transformers import ...} in .py files and
 * Jupyter notebooks only; a click opens the project's website. One icon per line: on
 * {@code import os, numpy, pandas} only NumPy's, the leftmost known package. Registered for the Python
 * language (META-INF/conda-python.xml), which also covers the code cells of notebooks.
 */
public final class ImportLineMarkerProvider extends LineMarkerProviderDescriptor implements DumbAware {

  private static final Pattern IMPORT = Pattern.compile("[ \\t]*import[ \\t]+");
  /** One item of {@code import a.b as c, d}: group 1 is the top-level module. */
  private static final Pattern IMPORT_ITEM = Pattern.compile(
      "\\G[ \\t]*([A-Za-z_]\\w*)(?:[ \\t]*\\.[ \\t]*[A-Za-z_]\\w*)*(?:[ \\t]+as[ \\t]+[A-Za-z_]\\w*)?[ \\t]*(?:,|;|#|\\\\|$)");
  /** {@code from a.b import c}; relative imports ({@code from .x}) never match. */
  private static final Pattern FROM = Pattern.compile("[ \\t]*from[ \\t]+([A-Za-z_]\\w*)");

  @Override
  public @NotNull String getName() {
    return "Package logos on imports";
  }

  @Override
  public @Nullable Icon getIcon() {
    return CondaIcons.CONDA;
  }

  @Override
  public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
    if (element.getFirstChild() != null || element.getTextLength() > 40) return null;
    KnownPackage pkg = KnownPackage.byImport(element.getText());   // cheap filter: most leaves are not package names
    if (pkg == null) return null;
    PsiFile file = element.getContainingFile();
    if (file == null || !isPythonFileOrNotebook(file)) return null;
    Document doc = file.getViewProvider().getDocument();
    if (doc == null) return null;

    TextRange range = element.getTextRange();
    int line = doc.getLineNumber(range.getStartOffset());
    int lineStart = doc.getLineStartOffset(line);
    CharSequence text = doc.getCharsSequence().subSequence(lineStart, doc.getLineEndOffset(line));
    if (firstKnownModuleStart(text) != range.getStartOffset() - lineStart) return null;   // one icon per line

    return new LineMarkerInfo<>(element, range, pkg.icon(),
        e -> pkg.title() + ": " + pkg.url(),
        (mouseEvent, e) -> BrowserUtil.browse(pkg.url()),
        GutterIconRenderer.Alignment.RIGHT,
        pkg::title);
  }

  /** Offset, within the line, of the leftmost imported module that is a {@link KnownPackage}; -1 if none. */
  static int firstKnownModuleStart(CharSequence line) {
    for (int start : importedModuleStarts(line)) {
      int end = start;
      while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
      if (KnownPackage.byImport(line.subSequence(start, end).toString()) != null) return start;
    }
    return -1;
  }

  /** Offsets, within the line, of the top-level modules an import statement on this line names. */
  static List<Integer> importedModuleStarts(CharSequence line) {
    List<Integer> starts = new ArrayList<>();
    Matcher from = FROM.matcher(line);
    if (from.lookingAt()) {
      starts.add(from.start(1));
      return starts;
    }
    Matcher head = IMPORT.matcher(line);
    if (!head.lookingAt()) return starts;
    Matcher item = IMPORT_ITEM.matcher(line);
    int pos = head.end();
    while (pos < line.length() && item.find(pos)) {
      starts.add(item.start(1));
      if (item.end() == pos) break;   // no progress: stop
      pos = item.end();
      char last = line.charAt(pos - 1);
      if (last != ',') break;         // ;, # or \ ends the module list
    }
    return starts;
  }

  /** .py files and .ipynb notebooks only: not .pyi stubs, not Python injected into other files. */
  private static boolean isPythonFileOrNotebook(PsiFile file) {
    if (InjectedLanguageManager.getInstance(file.getProject()).isInjectedFragment(file)) return false;
    if ("Jupyter".equals(file.getViewProvider().getBaseLanguage().getID())) return true;
    VirtualFile vf = file.getViewProvider().getVirtualFile();
    String ext = vf.getExtension();
    return "py".equalsIgnoreCase(ext) || "ipynb".equalsIgnoreCase(ext);
  }
}
