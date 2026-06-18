# Model Setup

This page answers: "How do I configure Talos to use a local model?"

## Current Support

Talos uses configurable local model engines. The current beta path favors
managed llama.cpp. Ollama support remains available only when explicitly
selected as the backend.

Talos does not install model weights during installation. Model setup is a
separate step.

Local-first depends on the configured chat endpoint. The chat transport does not yet enforce a localhost-only guard; a configured remote `ollama.host` can receive prompts. A remote `ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or `TALOS_ENGINE_HOST` means full prompts, including retrieved file contents, can be sent to that host.

## Show Setup Help

```powershell
talos setup models
```

This prints the tested managed profiles and example commands.

## Tested Managed Profiles

| Profile | Source | File |
| --- | --- | --- |
| `qwen2.5-coder-14b` | `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF` | `qwen2.5-coder-14b-instruct-q4_k_m.gguf` |
| `gpt-oss-20b` | `ggml-org/gpt-oss-20b-GGUF` | `gpt-oss-20b-mxfp4.gguf` |

Configure Qwen:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
```

Configure GPT-OSS:

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

## Required Server Path

`--server-path` must point to an existing local `llama-server.exe` file.

If the file does not exist, setup fails instead of writing a broken
configuration.

## User Config Path

Talos writes model configuration to:

```text
%USERPROFILE%\.talos\config.yaml
```

If the file already exists, setup refuses to overwrite it unless `--force` is
used. When `--force` is used, Talos writes a backup first.

## Talos-Owned Model Cache

For tested managed profiles, Talos configures the Hugging Face cache directory:

```text
%USERPROFILE%\.talos\models\huggingface
```

The directory is created when the managed llama.cpp server starts. The model is
downloaded through llama.cpp on first model start when the configured Hugging
Face source is reachable.

## User-Owned GGUF Model

If you already keep a GGUF model elsewhere, configure a direct path:

```powershell
talos setup models --profile my-agent --server-path C:/path/to/llama-server.exe --model-path D:/models/agent.gguf --write
```

`--model-path` must point to an existing file.

## Verify Model Setup

```powershell
talos status --verbose
```

Check:

- backend
- model
- engine host
- health
- config loaded path
- user config status

Inside the REPL, use `/models` to list visible models and
`/set model <backend/model>` to switch the active chat model.
