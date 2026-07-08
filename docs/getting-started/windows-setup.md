# Windows setup

Use this guide after installing Talos on Windows x64.

Talos installs the CLI and bundled Java runtime. It does not install llama.cpp or model weights.

## 1. Install Talos

```powershell
iwr https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.ps1 -OutFile install-talos.ps1
powershell -ExecutionPolicy Bypass -File .\install-talos.ps1 -Version 0.10.8 -Force -AllowUnsigned
talos --version
```

Open a new PowerShell window after install if `talos` is not found.

## 2. Download llama.cpp

Use the pinned beta lane:

- Release: https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- Windows x64 CPU artifact: `llama-b9860-bin-win-cpu-x64.zip`

Extract it somewhere you control, for example:

```text
C:\Users\<you>\Tools\llama-b9860\
```

The server path should point to `llama-server.exe` inside the extracted folder.

## 3. Download Qwen

Use the accepted beta coding profile first. It is a large CPU model, not a low-resource default:

- Model page: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Save the file somewhere stable, for example:

```text
C:\Users\<you>\.talos\models\qwen2.5-coder-14b-instruct-q4_k_m.gguf
```

## 4. Configure Talos

Replace the example paths with your real paths:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path "C:\Users\<you>\Tools\llama-b9860\llama-server.exe" --model-path "C:\Users\<you>\.talos\models\qwen2.5-coder-14b-instruct-q4_k_m.gguf" --write --force
```

Restart Talos after the config write.

## 5. Verify

```powershell
talos status --verbose
talos doctor --start
```

Do not treat the model as ready until `talos doctor --start` completes the end-to-end model smoke. If doctor reports a slow smoke warning, Talos is configured, but normal edit turns may be too slow on that machine.
