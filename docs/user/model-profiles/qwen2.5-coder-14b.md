# qwen2.5-coder-14b Model Profile

Support level: accepted beta stability profile.

Source: `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF`

GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Tool mode: native/default (`tools.native_calling: true`)

## Configure

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
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

Known residual: Qwen can still under-ground factual workspace answers or choose
weak edit strategies on some prompts. Talos steers several detectable shapes
deterministically, including ungrounded no-results claims and append-shaped
full-file writes, but that is not a proof that Qwen will always produce a
useful grounded answer. Treat weak or generic Qwen answers as model-competence
findings unless trace/tool evidence shows a runtime policy failure.
