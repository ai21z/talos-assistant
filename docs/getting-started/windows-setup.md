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

The setup wizard (`talos setup wizard`) can install a pinned, SHA-256-verified llama.cpp lane for you. On Windows it selects the lane from detected NVIDIA driver evidence and always falls back to CPU when no compatible driver is found:

- Release: https://github.com/ggml-org/llama.cpp/releases/tag/b9918
- Windows x64 CPU artifact: `llama-b9918-bin-win-cpu-x64.zip`
- Windows x64 CUDA 12.4 artifacts (NVIDIA driver 551.61 or newer): `llama-b9918-bin-win-cuda-12.4-x64.zip` plus driver runtime `cudart-llama-bin-win-cuda-12.4-x64.zip`
- Windows x64 CUDA 13.3 artifacts (NVIDIA driver 580 or newer): `llama-b9918-bin-win-cuda-13.3-x64.zip` plus driver runtime `cudart-llama-bin-win-cuda-13.3-x64.zip`

The wizard states the tag, asset names, and SHA-256 digests, and asks before downloading anything. CUDA lanes need both archives, with the cudart DLLs extracted next to `llama-server.exe`.

For a manual install, download and extract the matching archives somewhere you control, for example:

```text
C:\Users\<you>\Tools\llama-b9918\
```

The server path should point to `llama-server.exe` inside the extracted folder. `talos doctor --start` verifies that the managed server starts, answers a smoke prompt, and reports measured rates. Whether GPU offload is actually active is verified by `talos tune` from the server log before it keeps a GPU-lane config, not assumed from the binary name.

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
talos setup models --profile qwen2.5-coder-14b --server-path "C:\Users\<you>\Tools\llama-b9918\llama-server.exe" --model-path "C:\Users\<you>\.talos\models\qwen2.5-coder-14b-instruct-q4_k_m.gguf" --write --force
```

Restart Talos after the config write.

## 5. Verify

```powershell
talos status --verbose
talos doctor --start
```

## 6. Tune (optional)

`talos tune` detects your hardware read-only, proposes an exact config diff (engine lane, context window, no GPU layer flags), applies it only after you approve with a timestamped backup, and then verifies GPU offload and generation rates from the server log. If verification cannot prove the GPU lane, it restores your previous config.

```powershell
talos tune
```

Do not treat the model as ready until `talos doctor --start` completes the end-to-end model smoke. If doctor reports a slow smoke warning, Talos is configured, but normal edit turns may be too slow on that machine.
