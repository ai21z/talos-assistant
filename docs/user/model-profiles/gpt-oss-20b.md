# gpt-oss-20b Model Profile

Support level: accepted beta stability profile.

Source: `ggml-org/gpt-oss-20b-GGUF`

GGUF file: `gpt-oss-20b-mxfp4.gguf`

Tool mode: native/default (`tools.native_calling: true`)

## Configure

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
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

This profile is one of the doctrine-pinned stability models. Do not swap it out as a fix for model instability; fix the harness, prompt, config, or guide evidence instead.
