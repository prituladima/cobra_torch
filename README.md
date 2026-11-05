# Cobra torch

IntelliJ IDEA plugin for conda environment files named `.conda_env.yml`:

- the conda icon instead of the YAML icon (needs the Python plugin);
- gutter, line 1, refresh icon: `conda env update -f .conda_env.yml --prune`;
- gutter, line 2, trash icon: `conda env remove -n <name>`, with `<name>` taken from the file
  (conda has no `env remove -f <file>`);
- a line above line 1: `Conda env is not installed`, or `Environment takes 7.10 GB`;
- the official logo next to well-known dependencies (`KnownPackage.java`) and the conda icon next to
  the `conda-forge` channel (`KnownChannel.java`); a click opens the project's website;
- the same logos next to `import x` / `from x import ...` in `.py` files and Jupyter notebooks only
  (not `.pyi`, not Python embedded in other files); import names such as `sklearn`, `PIL` and
  `IPython` map to their packages;
- **File | New | Conda Env File**, from `src/main/resources/templates/conda_env.yml`.

The conda commands run in a new Terminal tab, in the file's directory, after saving the file.
If no terminal API is available, the command is copied to the clipboard instead.

The environment line is the total size of the files in the environment's folder, measured in the
background (files hard-linked from conda's package cache count in full, as with `du -l`; for `base`,
`envs/` and `pkgs/` are left out). It updates by itself within about 5 seconds of conda changing the
environment. The folder is found without running conda: `envs_dirs` in `.condarc`, `<conda>/envs` of
the usual install locations on Linux, macOS and Windows, `~/.conda/envs`, and `~/.conda/environments.txt`.

Both can be turned off: **Settings | Editor | Inlay Hints | Other | YAML | Conda environment size**,
and **Settings | Editor | General | Gutter Icons | Package logos on imports**.

## Build

```sh
./gradlew build        # Linux, macOS
gradlew.bat build      # Windows
```

The installable plugin is `build/distributions/cobra-torch-<version>.zip`:
**Settings | Plugins | ⚙ | Install Plugin from Disk...**

`./gradlew runIde` starts a sandbox IDE with the plugin installed.

Gradle needs a JDK 17+ (`JAVA_HOME`, or `java` on `PATH`). The first build downloads, and caches in
`~/.gradle`: Gradle itself, a JDK 25 for compiling (unless one is installed), IntelliJ IDEA
(`platformVersion`) and the Python plugin (`pythonPluginVersion`).

## IDE versions

The plugin declares `since-build="262"` (IntelliJ IDEA 2026.2) and no upper bound, so it stays enabled
after IDE updates. `./gradlew verifyPlugin` checks binary compatibility against the current IDE releases.
The experimental terminal API is isolated: if a later IDE removes it, the plugin falls back to the
older terminal API, then to the clipboard. To compile against a newer IDE, change `platformVersion`
and `pythonPluginVersion` in `gradle.properties`.

## Linux, macOS, Windows

The build and the plugin are the same on all three. `.gitattributes` keeps `gradlew` with LF and
`gradlew.bat` with CRLF line endings. The terminal starts in the OS-native directory, and new files
use the IDE's line-separator setting. The `conda` command must work in the IDE's terminal shell:
run `conda init bash`, `conda init zsh` or `conda init powershell` once.

## Layout

```
build.gradle.kts, settings.gradle.kts, gradle.properties   build configuration
gradlew, gradlew.bat, gradle/wrapper/                       Gradle wrapper
src/main/java/com/prituladima/plugin/intelij/               sources
src/main/resources/META-INF/plugin.xml                      plugin descriptor
src/main/resources/META-INF/conda-python.xml                extensions loaded only with the Python plugin
src/main/resources/META-INF/pluginIcon.svg, pluginIcon_dark.svg   plugin logo (Settings | Plugins), light / dark theme
cobra_torch_minimal.svg                                     source of the plugin logo
src/main/resources/icons/packages/                          dependency logos
src/main/resources/messages/CondaEnvBundle.properties       names shown in Settings
src/main/resources/templates/conda_env.yml                  File | New template
```

## Adding a dependency logo

Add a constant to `KnownPackage` and its logo to `src/main/resources/icons/packages/`: a 16x16 SVG,
or a 16x16 PNG plus a 32x32 `<name>@2x.png`. An optional `<name>_dark.svg` is used by dark themes.

## Logo sources

Each logo is the project's own, cropped to a square 16x16 mark; editor metadata, CSS classes and
wordmarks were removed. Projects without a logo of their own use their organisation's or parent
project's. The logos are trademarks of their respective owners.

| File | Used for | Source |
|---|---|---|
| `python.svg` | python | python.org/community/logos, `python-logo-only.svg` (drop shadow removed) |
| `numpy.svg` | numpy | numpy.org/images/`logo.svg` |
| `pandas.svg`, `pandas_dark.svg` | pandas, pandas-stubs | pandas.pydata.org/static/img/`pandas_mark.svg`, `pandas_mark_white.svg` |
| `scipy.svg` | scipy, scipy-stubs | github.com/scipy/scipy, `doc/source/_static/logo.svg` |
| `scikit-learn.svg` | scikit-learn | github.com/scikit-learn/scikit-learn, `doc/logos/scikit-learn-logo-without-subtitle.svg`, the two blobs as in its favicon |
| `statsmodels.svg`, `statsmodels_dark.svg` | statsmodels | github.com/statsmodels/statsmodels, `docs/source/images/statsmodels-logo-v2-no-text.svg`, `-no-text-light.svg` |
| `lightgbm.svg` | lightgbm | github.com/lightgbm-org/LightGBM, `docs/logo/LightGBM_logo_black_text.svg`, the mark only |
| `matplotlib.svg` | matplotlib | github.com/matplotlib/matplotlib, `lib/matplotlib/mpl-data/images/matplotlib.svg` |
| `plotly.svg`, `plotly_dark.svg` | plotly | plotly.com/all_static/images/`logo-black.svg`, `logo-white.svg`, the mark only |
| `pillow.png` | pillow | github.com/python-pillow/pillow-logo, `pillow-logo-248x250.png` |
| `tqdm.png` | tqdm | github.com/tqdm/tqdm, `logo.png` |
| `jupyter.svg` | jupyter, jupyterlab, notebook, ipywidgets | github.com/jupyter/design, `logos/Logo Mark/logomark-orangebody-greyplanets` |
| `ipython.png` | ipython, ipykernel | ipython.org, `ipython.github.com/ipython-logo.png` (the site icon) |
| `gradio.svg` | gradio | github.com/gradio-app/gradio, `js/_website/src/lib/assets/brand-assets/gradio-logo.svg` |
| `pytorch.svg` | torch, torchvision, torchaudio, torchao | pytorch.org/wp-content/uploads/2024/10/`logo.svg`, the symbol only |
| `torchmetrics.svg` | torchmetrics | github.com/Lightning-AI/torchmetrics, `docs/source/_static/images/icon.svg` |
| `triton.png` | triton | triton-lang.org, cdn.openai.com/triton/assets/`triton-logo.png` |
| `liger-kernel.png` | liger-kernel | github.com/linkedin/Liger-Kernel, `docs/images/logo-banner.png`, the mark only |
| `huggingface.svg` | transformers, datasets, huggingface_hub, safetensors, tokenizers, accelerate, peft, trl, diffusers, evaluate, timm | huggingface.co/front/assets/`huggingface_logo-noborder.svg` |
| `sentence-transformers.png` | sentence-transformers | sbert.net/_static/`favicon.ico` |
| `vllm.svg` | vllm | vllm.ai/`vLLM-Logo.svg` (the site icon) |
| `llm-compressor.png` | llmcompressor | github.com/vllm-project/llm-compressor, `docs/assets/llmcompressor-icon.png` |
| `outlines.svg` | outlines | github.com/dottxt-ai/outlines, `docs/assets/images/logo-square.svg` |
| `eleutherai.svg`, `eleutherai_dark.svg` | lm-eval | eleuther.ai/assets/`eleutherai-logo.svg`, the mark only (white on dark, as in its favicon) |
| `transformer-lens.png` | transformer-lens | github.com/TransformerLensOrg/TransformerLens, `assets/transformer_lens_logo.png` |
| `beir.png` | beir | github.com/beir-cellar/beir, `images/color_logo_transparent_cropped.png`, the mark only |
| `kaggle.png` | kagglehub, kaggle | kaggle.com/static/images/`favicon-new.ico`, its 16 and 32 px frames |
| `wandb.svg` | wandb | github.com/wandb/wandb, `assets/logo.svg`, the dot mark on its background |
| conda icon (Python plugin) | conda-forge channel | `PythonCommunityImplCondaIcons.Anaconda` |

No official logo was found (repository, docs site and favicon checked) for pip, bitsandbytes, faiss,
h5py, flash-linear-attention, autoawq, jiwer, sacrebleu, pycocoevalcap, rank-bm25, soundfile, gdown,
jsonschema, lpips, mamba-ssm, nvidia-modelopt, rouge-score, sae-lens, sageattention, torch-pruning and
torchprofile, so they get none.
