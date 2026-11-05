package com.prituladima.plugin.intellij;

import com.intellij.codeInsight.hints.declarative.AboveLineIndentedPosition;
import com.intellij.codeInsight.hints.declarative.HintFormat;
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector;
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.codeInsight.hints.declarative.InlayTreeSink;
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiFile;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A line above line 1 of .conda_env.yml: "Conda env is not installed" or "Environment takes 2.07 GB".
 * Settings | Editor | Inlay Hints | Other | YAML can turn it off.
 */
public final class CondaEnvSizeInlayProvider implements InlayHintsProvider, DumbAware {

  @Override
  public @Nullable InlayHintsCollector createCollector(@NotNull PsiFile file, @NotNull Editor editor) {
    if (!CondaEnv.isCondaEnvFile(file.getViewProvider().getVirtualFile())) return null;
    return new OwnBypassCollector() {
      @Override
      public void collectHintsForFile(@NotNull PsiFile psi, @NotNull InlayTreeSink sink) {
        String env = CondaEnv.envName(psi.getViewProvider().getContents());
        if (env == null) return;
        CondaEnvSizes.Status status = CondaEnvSizes.getInstance().status(env);
        sink.addPresentation(new AboveLineIndentedPosition(0, 0, 0), null, status.tooltip(),
            HintFormat.Companion.getDefault(),
            builder -> {
              builder.text(status.label(), null);
              return Unit.INSTANCE;
            });
      }
    };
  }
}
