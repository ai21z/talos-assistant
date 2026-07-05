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

For Windows x64 (CPU), choose the matching Windows x64 CPU artifact from the same llama.cpp release and configure it with `talos setup models`.

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
