# Model setup

Talos works best with a local `llama.cpp` server path and an explicit GGUF model path. The setup wizard handles the first Ubuntu/WSL x64 CPU lane.

There are two setup levels:

- Guided Ubuntu/WSL setup, where `talos setup wizard` can install the pinned CPU engine lane and help configure an accepted model.
- Direct setup, where you provide the `llama-server` path and GGUF model path yourself.

```bash
talos setup wizard
talos doctor --start
```

Windows users should use:

```powershell
talos setup models
talos doctor --start
```

## Where to get `llama-server`

The current pinned Ubuntu/WSL x64 CPU lane uses the llama.cpp `b9860` release, and the Windows lanes use `b9918`:

- Ubuntu release: https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- Ubuntu x64 (CPU): `llama-b9860-bin-ubuntu-x64.tar.gz`
- Windows release: https://github.com/ggml-org/llama.cpp/releases/tag/b9918
- Windows x64 (CPU): `llama-b9918-bin-win-cpu-x64.zip`
- Windows x64 (CUDA 12.4, NVIDIA driver 551.61+): `llama-b9918-bin-win-cuda-12.4-x64.zip` plus `cudart-llama-bin-win-cuda-12.4-x64.zip`
- Windows x64 (CUDA 13.3, NVIDIA driver 580+): `llama-b9918-bin-win-cuda-13.3-x64.zip` plus `cudart-llama-bin-win-cuda-13.3-x64.zip`

The setup wizard selects the Windows lane from detected NVIDIA driver evidence and falls back to CPU when no compatible driver is detected. `talos doctor --start` verifies that the managed server starts, answers a smoke prompt, and reports measured rates. GPU offload is verified by `talos tune` from the server log before it keeps a GPU-lane config, not assumed from the binary name.

The first accepted model to try is Qwen. It is a large CPU model, not a low-resource default:

- Model page: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Talos does not claim arbitrary latest upstream builds are verified. Use the pinned lane when you need reproducible setup evidence.

## Profiles

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. Both are large local-model lanes. See [Model Profiles](../reference/model-profiles.md) for exact guidance.

After any setup change, run:

```bash
talos doctor --start
```

Do not treat a profile as ready until doctor completes the end-to-end model smoke. If doctor reports a slow smoke warning, the setup is functional but may be too slow for normal editing work on that machine.
