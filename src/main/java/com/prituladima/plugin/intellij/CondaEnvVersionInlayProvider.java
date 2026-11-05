package com.prituladima.plugin.intellij;

import com.intellij.codeInsight.hints.declarative.HintFormat;
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector;
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider;
import com.intellij.codeInsight.hints.declarative.InlayTreeSink;
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition;
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiFile;
import kotlin.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * The installed version right after each dependency of .conda_env.yml, as a gray hint that is not part of
 * the file: {@code - numpy [2.3.1]}. Not shown for a dependency whose version the file already states
 * ({@code - numpy=2.3}, {@code - torch>=2.6}). The versions come from the environment the toolbar
 * describes: this computer's, or the remote computer's while connected.
 * Settings | Editor | Inlay Hints | Values | YAML can turn it off.
 */
public final class CondaEnvVersionInlayProvider implements InlayHintsProvider, DumbAware {

  @Override
  public @Nullable InlayHintsCollector createCollector(@NotNull PsiFile file, @NotNull Editor editor) {
    if (!CondaEnv.isCondaEnvFile(file.getViewProvider().getVirtualFile())) return null;
    return new OwnBypassCollector() {
      @Override
      public void collectHintsForFile(@NotNull PsiFile psi, @NotNull InlayTreeSink sink) {
        Document doc = psi.getViewProvider().getDocument();
        String env = doc == null ? null : CondaEnv.envName(doc.getCharsSequence());
        if (env == null) return;
        RemoteHost.Remote remote = RemoteHost.connected(psi.getProject());
        InstalledVersions versions;
        String where;
        if (remote != null) {
          versions = remote.versions(env);
          where = remote.label();
        } else {
          Path prefix = CondaEnvLocator.find(env);
          versions = prefix == null ? null : InstalledVersions.local(prefix);
          where = "this computer";
        }
        if (versions == null) return;
        String tooltip = "Installed in " + env + " on " + where;
        for (EnvFileItems.Item item : EnvFileItems.parse(doc)) {
          if (!"dependencies".equals(item.section()) || item.pinned()) continue;
          String version = versions.version(item);
          if (version == null) continue;
          sink.addPresentation(new InlineInlayPosition(item.nameEnd(), true, 0), null, tooltip,
              HintFormat.Companion.getDefault(),
              builder -> {
                builder.text(version, null);
                return Unit.INSTANCE;
              });
        }
      }
    };
  }
}
