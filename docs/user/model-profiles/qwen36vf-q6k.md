# qwen36vf-q6k Model Profile

Support level: experimental selectable profile, not a beta stability baseline.

Source: `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF`

GGUF file: `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf`

Tool mode: native/default (`tools.native_calling: true`)

## Configure

```powershell
talos setup models --profile qwen36vf-q6k --server-path C:/path/to/llama-server.exe --write
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

Qwen3.6-VibeForged Q4/Q6 passed the initial Talos tool-call gate in native/default mode, but this profile is still experimental selectable. Do not present it as an accepted beta stability profile without a separate live audit packet.
