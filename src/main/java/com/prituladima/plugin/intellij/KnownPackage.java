package com.prituladima.plugin.intellij;

import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Dependencies that get their official logo in the gutter; a click opens the project's website:
 * its own site if it has one, else its official docs site, else its repository.
 * Projects without a logo of their own use their organization's (Hugging Face, PyTorch, Kaggle,
 * EleutherAI) or parent project's (Jupyter, IPython, pandas, SciPy).
 * <p>
 * To add one: a constant here and its logo in {@code resources/icons/packages}: a 16x16 SVG, or a
 * 16x16 PNG plus a 32x32 {@code <name>@2x.png}; an optional {@code <name>_dark.svg} is used by dark themes.
 */
enum KnownPackage implements GutterLink {
  // Python and the scientific stack
  PYTHON("Python", "https://www.python.org", "python.svg", "python"),
  NUMPY("NumPy", "https://numpy.org", "numpy.svg", "numpy"),
  PANDAS("pandas", "https://pandas.pydata.org", "pandas.svg", "pandas"),
  PANDAS_STUBS("pandas-stubs", "https://pandas.pydata.org", "pandas.svg", "pandas-stubs"),
  SCIPY("SciPy", "https://scipy.org", "scipy.svg", "scipy"),
  SCIPY_STUBS("scipy-stubs", "https://scipy.org", "scipy.svg", "scipy-stubs"),
  SCIKIT_LEARN("scikit-learn", "https://scikit-learn.org", "scikit-learn.svg", "scikit-learn"),
  STATSMODELS("statsmodels", "https://www.statsmodels.org", "statsmodels.svg", "statsmodels"),
  LIGHTGBM("LightGBM", "https://lightgbm.readthedocs.io", "lightgbm.svg", "lightgbm"),
  MATPLOTLIB("Matplotlib", "https://matplotlib.org", "matplotlib.svg", "matplotlib", "matplotlib-base"),
  PLOTLY("Plotly", "https://plotly.com/python/", "plotly.svg", "plotly"),
  PILLOW("Pillow", "https://python-pillow.org", "pillow.png", "pillow"),
  TQDM("tqdm", "https://tqdm.github.io", "tqdm.png", "tqdm"),

  // Jupyter
  JUPYTER("Jupyter", "https://jupyter.org", "jupyter.svg", "jupyter"),
  JUPYTERLAB("JupyterLab", "https://jupyter.org", "jupyter.svg", "jupyterlab"),
  NOTEBOOK("Jupyter Notebook", "https://jupyter.org", "jupyter.svg", "notebook"),
  IPYWIDGETS("ipywidgets", "https://jupyter.org", "jupyter.svg", "ipywidgets"),
  IPYTHON("IPython", "https://ipython.org", "ipython.png", "ipython"),
  IPYKERNEL("ipykernel", "https://ipython.org", "ipython.png", "ipykernel"),
  GRADIO("Gradio", "https://www.gradio.app", "gradio.svg", "gradio"),

  // PyTorch
  TORCH("PyTorch", "https://pytorch.org", "pytorch.svg", "torch", "pytorch", "pytorch-cuda", "pytorch-gpu"),
  TORCHVISION("torchvision", "https://docs.pytorch.org/vision", "pytorch.svg", "torchvision"),
  TORCHAUDIO("torchaudio", "https://docs.pytorch.org/audio", "pytorch.svg", "torchaudio"),
  TORCHAO("torchao", "https://docs.pytorch.org/ao", "pytorch.svg", "torchao"),
  TORCHMETRICS("TorchMetrics", "https://lightning.ai/docs/torchmetrics", "torchmetrics.svg", "torchmetrics"),
  TRITON("Triton", "https://triton-lang.org", "triton.png", "triton"),
  LIGER_KERNEL("Liger Kernel", "https://github.com/linkedin/Liger-Kernel", "liger-kernel.png", "liger-kernel"),

  // Hugging Face
  TRANSFORMERS("Transformers", "https://huggingface.co/docs/transformers", "huggingface.svg", "transformers"),
  DATASETS("Hugging Face Datasets", "https://huggingface.co/docs/datasets", "huggingface.svg", "datasets"),
  HUGGINGFACE_HUB("Hugging Face Hub", "https://huggingface.co/docs/huggingface_hub", "huggingface.svg", "huggingface-hub"),
  SAFETENSORS("safetensors", "https://huggingface.co/docs/safetensors", "huggingface.svg", "safetensors"),
  TOKENIZERS("Tokenizers", "https://huggingface.co/docs/tokenizers", "huggingface.svg", "tokenizers"),
  ACCELERATE("Accelerate", "https://huggingface.co/docs/accelerate", "huggingface.svg", "accelerate"),
  PEFT("PEFT", "https://huggingface.co/docs/peft", "huggingface.svg", "peft"),
  TRL("TRL", "https://huggingface.co/docs/trl", "huggingface.svg", "trl"),
  DIFFUSERS("Diffusers", "https://huggingface.co/docs/diffusers", "huggingface.svg", "diffusers"),
  EVALUATE("Evaluate", "https://huggingface.co/docs/evaluate", "huggingface.svg", "evaluate"),
  TIMM("timm", "https://huggingface.co/docs/timm", "huggingface.svg", "timm"),
  SENTENCE_TRANSFORMERS("Sentence Transformers", "https://www.sbert.net", "sentence-transformers.png", "sentence-transformers"),

  // LLM serving, compression, evaluation, interpretability
  VLLM("vLLM", "https://vllm.ai", "vllm.svg", "vllm"),
  LLM_COMPRESSOR("LLM Compressor", "https://docs.vllm.ai/projects/llm-compressor", "llm-compressor.png", "llmcompressor", "llm-compressor"),
  OUTLINES("Outlines", "https://dottxt-ai.github.io/outlines", "outlines.svg", "outlines"),
  LM_EVAL("LM Evaluation Harness", "https://github.com/EleutherAI/lm-evaluation-harness", "eleutherai.svg", "lm-eval"),
  TRANSFORMER_LENS("TransformerLens", "https://transformerlensorg.github.io/TransformerLens", "transformer-lens.png", "transformer-lens"),
  BEIR("BEIR", "https://github.com/beir-cellar/beir", "beir.png", "beir"),

  // Data and experiment tracking
  KAGGLEHUB("kagglehub", "https://github.com/Kaggle/kagglehub", "kaggle.svg", "kagglehub"),
  KAGGLE("Kaggle CLI", "https://github.com/Kaggle/kaggle-cli", "kaggle.svg", "kaggle"),
  WANDB("Weights & Biases", "https://wandb.ai", "wandb.svg", "wandb");

  private final String title;
  private final String url;
  private final Icon icon;
  private final String[] names;

  KnownPackage(String title, String url, String logo, String... names) {
    this.title = title;
    this.url = url;
    this.icon = IconLoader.getIcon("/icons/packages/" + logo, KnownPackage.class);
    this.names = names;
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  public String url() {
    return url;
  }

  @Override
  public Icon icon() {
    return icon;
  }

  /** Conda and pip names are case-insensitive, and pip treats runs of -, _ and . alike. */
  static @Nullable KnownPackage byName(String name) {
    String n = name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    for (KnownPackage p : values()) {
      for (String alias : p.names) {
        if (alias.equals(n)) return p;
      }
    }
    return null;
  }

  /** Top-level Python module -> package: the package name with _ for -, plus modules named otherwise. */
  private static final Map<String, KnownPackage> BY_IMPORT = byImportTable();

  private static Map<String, KnownPackage> byImportTable() {
    Map<String, KnownPackage> map = new HashMap<>();
    for (KnownPackage p : values()) {
      if (p == PYTHON) continue;
      for (String alias : p.names) map.putIfAbsent(alias.replace('-', '_'), p);
    }
    map.put("sklearn", SCIKIT_LEARN);
    map.put("PIL", PILLOW);
    map.put("IPython", IPYTHON);
    return Map.copyOf(map);
  }

  /** The package of an imported top-level module ({@code numpy}, {@code sklearn}, {@code PIL}); case-sensitive like Python. */
  static @Nullable KnownPackage byImport(String module) {
    return BY_IMPORT.get(module);
  }
}
