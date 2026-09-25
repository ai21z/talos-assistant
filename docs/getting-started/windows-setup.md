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

For the published 0.10.8 Windows artifact, a compatible engine must be downloaded and configured manually. The current source pins these llama.cpp Windows artifacts:

- Release: https://github.com/ggml-org/llama.cpp/releases/tag/b9918
- Windows x64 CPU artifact: `llama-b9918-bin-win-cpu-x64.zip`
- Windows x64 CUDA 12.4 artifacts (NVIDIA driver 551.61 or newer): `llama-b9918-bin-win-cuda-12.4-x64.zip` plus driver runtime `cudart-llama-bin-win-cuda-12.4-x64.zip`
- Windows x64 CUDA 13.3 artifacts (NVIDIA driver 580 or newer): `llama-b9918-bin-win-cuda-13.3-x64.zip` plus driver runtime `cudart-llama-bin-win-cuda-13.3-x64.zip`

CUDA lanes need both archives, with the cudart DLLs extracted next to `llama-server.exe`.

For a manual install, download and extract the matching archives somewhere you control, for example:

```text
C:\Users\<you>\Tools\llama-b9918\
```

The server path should point to `llama-server.exe` inside the extracted folder. `talos doctor --start` checks managed server startup and an end-to-end smoke reply. Current-source rate reporting is conditional on measurable timing evidence, not guaranteed for every server response.

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
talos setup models --profile qwen2.5-coder-14b --server-path "C:\Users\<you>\Tools\llama-b9918\llama-server.exe" --model-path "C:\Users\<you>\.talos\models\qwen2.5-coder-14b-instruct-q4_k_m.gguf" --write
```

Without `--write`, model setup prints help only. If configuration already exists, replacement requires an explicit `--force`, and a backup is created first. Talos must be restarted after a configuration write.

## 5. Verify

```powershell
talos status --verbose
talos doctor --start
```

## 6. Unreleased setup and tuning

Automatic Windows engine installation and `talos tune` are current-source capabilities, not capabilities of the published 0.10.8 Windows artifact.

In current source, a pinned, SHA-256-verified Windows engine lane can be installed through `talos setup wizard`. The lane is selected from detected NVIDIA driver evidence, with CPU selected when no compatible driver is detected. The tag, asset names and hashes are shown, and approval is requested before download.

GPU offload is verified by `talos tune` from the server log before a GPU-lane configuration is kept, not assumed from the binary name. A bounded configuration diff is proposed after read-only hardware detection. Changes are applied only after approval, with a timestamped backup. If verification cannot establish the proposed GPU lane, the previous configuration is restored.

```powershell
talos tune
```

Do not treat the model as ready until `talos doctor --start` completes the end-to-end model smoke. If doctor reports a slow smoke warning, Talos is configured, but normal edit turns may be too slow on that machine.
