# deepseek-v2lite-q4km Model Profile

Support level: experimental selectable profile, not a beta stability baseline.

Source: `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF`

GGUF file: `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf`

Tool mode: text/tool-prompt (`tools.native_calling: false`)

## Configure

```powershell
talos setup models --profile deepseek-v2lite-q4km --server-path C:/path/to/llama-server.exe --write
```

Use `--force` only after reviewing the existing `%USERPROFILE%\.talos\config.yaml`; Talos writes a backup before replacing it.

## Verify

Restart Talos after the config write, then run:

```powershell
talos status --verbose
talos doctor --start
```

`talos doctor --start` starts the managed llama.cpp server, loads the model, asks for the `TALOS_MODEL_SMOKE_OK` token, and releases the managed server afterwards.

Save the `talos doctor --start` output as evidence before calling this profile verified on a new machine.

## Notes

DeepSeek-Coder-V2-Lite Q4 is Talos-usable in text/tool-prompt mode with `tools.native_calling:false`; native/default produced zero executable tool calls. Do not present it as native/default compatible unless later evidence proves native/default tool-calling.
