# gpt-oss-20b Model Profile

Support level: accepted beta stability profile.

Source: `ggml-org/gpt-oss-20b-GGUF`

GGUF file: `gpt-oss-20b-mxfp4.gguf`

Tool mode: native/default (`tools.native_calling: true`)

## Configure

Preferred guided path:

```powershell
talos setup wizard
```

Direct setup requires a local GGUF path:

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --model-path D:/models/gpt-oss-20b-mxfp4.gguf --write
```

If `--model-path` is omitted, Talos accepts an exact
`gpt-oss-20b-mxfp4.gguf` already present in the standard Hugging Face cache and
writes that path into config. If no local file is found, setup refuses before it
writes config instead of relying on a managed remote preset.

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
