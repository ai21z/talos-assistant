# Linux setup

Use this guide for Ubuntu or WSL x64.

Talos installs the CLI and bundled Java runtime. The setup wizard can install the pinned CPU llama.cpp lane and configure an accepted model when you approve each step.

## 1. Install Talos

```bash
curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.sh | bash -s -- --version 0.10.8 --force
talos --version
```

The installer starts `talos setup wizard` unless you pass `--no-wizard`.

## 2. Use the wizard

If you skipped it during install, run:

```bash
talos setup wizard
```

For the first beta lane, accept the pinned llama.cpp CPU engine and choose `qwen2.5-coder-14b`.

The pinned engine source is:

- Release: https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- Ubuntu x64 CPU artifact: `llama-b9860-bin-ubuntu-x64.tar.gz`

The accepted Qwen model source is:

- Model page: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

## 3. Verify

```bash
talos status --verbose
talos doctor --start
```

Do not treat the model as ready until `talos doctor --start` completes the end-to-end model smoke.

## 4. Re-run setup later

```bash
talos setup wizard
```

Use the wizard again when you want to change the model profile or repair local paths.
