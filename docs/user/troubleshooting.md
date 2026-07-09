# Troubleshooting

Start with:

```bash
talos status --verbose
talos doctor
talos doctor --start
```

If the model server is down, verify that the configured server path and GGUF model path exist.

Use `talos status --verbose` to confirm the workspace, user config path, backend, model profile, host, and health result. Use `talos doctor --start` when you want Talos to start the managed server and prove the model can answer one smoke prompt.

## Where to get `llama-server`

For the pinned Ubuntu/WSL x64 CPU lane, use:

- https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- `llama-b9860-bin-ubuntu-x64.tar.gz`

For Windows x64, the pinned lanes come from the llama.cpp `b9918` release (https://github.com/ggml-org/llama.cpp/releases/tag/b9918) and are wizard-installable with SHA-256 verification. Configure a manual download with `talos setup models`.

- Windows x64 (CPU): `llama-b9918-bin-win-cpu-x64.zip`
- Windows x64 (CUDA 12.4, NVIDIA driver 551.61+): `llama-b9918-bin-win-cuda-12.4-x64.zip` plus `cudart-llama-bin-win-cuda-12.4-x64.zip`
- Windows x64 (CUDA 13.3, NVIDIA driver 580+): `llama-b9918-bin-win-cuda-13.3-x64.zip` plus `cudart-llama-bin-win-cuda-13.3-x64.zip`
- First accepted model to try: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Talos does not claim arbitrary latest upstream builds are verified. Prefer the pinned lane for release evidence.

If a prompt says work was not completed, inspect `/last trace` and the final files before retrying. A refusal or partial result is often the correct outcome when evidence is missing.

## Common symptoms

| Symptom | First check |
|---|---|
| Health is down | Server path, model path, port, and `doctor --start` output. |
| Ask or Plan will not edit | This is expected. Switch to `/mode agent` for supported writes. |
| Agent asks for approval | This is expected before writes and bounded command execution. |
| Final answer says partial or failed | Inspect `/last trace`, final files, and command or verifier output. |
| The wrong binary seems to run | Check `talos --version` and the resolved install path in your shell. |
