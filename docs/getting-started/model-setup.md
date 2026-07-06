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

The current pinned Ubuntu/WSL x64 CPU lane uses the llama.cpp `b9860` release:

- Release: https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- Ubuntu x64 (CPU): `llama-b9860-bin-ubuntu-x64.tar.gz`
- Windows x64 (CPU): `llama-b9860-bin-win-cpu-x64.zip`

The first accepted model to try is Qwen:

- Model page: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Talos does not claim arbitrary latest upstream builds are verified. Use the pinned lane when you need reproducible setup evidence.

## Profiles

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. See [Model Profiles](../reference/model-profiles.md) for exact guidance.

After any setup change, run:

```bash
talos doctor --start
```

Do not treat a profile as ready until doctor completes the end-to-end model smoke.
