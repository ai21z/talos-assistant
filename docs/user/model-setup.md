# Model Setup

This page answers: "How do I configure Talos to use a local model?"

## Current Support

Talos uses configurable local model engines. The current beta path favors
managed llama.cpp. Ollama support remains available only when explicitly
selected as the backend.

Talos does not install model weights during installation. Model setup is a
separate step.

Local-first depends on the configured chat endpoint. Chat endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend. When remote chat is explicitly allowed, full prompts, including retrieved file contents, can be sent to that host.

## Show Setup Help

```powershell
talos setup models
```

This prints the tested managed profiles and example commands.

## Tested Managed Profiles

| Profile | Source | File | Tool mode |
| --- | --- | --- | --- |
| `qwen2.5-coder-14b` | `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF` | `qwen2.5-coder-14b-instruct-q4_k_m.gguf` | native/default |
| `gpt-oss-20b` | `ggml-org/gpt-oss-20b-GGUF` | `gpt-oss-20b-mxfp4.gguf` | native/default |
| `qwen36vf-q4km` | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf` | native/default |
| `qwen36vf-q6k` | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf` | native/default |
| `deepseek-v2lite-q4km` | `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF` | `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` | text/tool-prompt |

Tool-mode evidence is per profile and quant. Qwen3.6-VibeForged Q4/Q6 passed
the initial Talos tool-call gate in native/default mode. DeepSeek-Coder-V2-Lite
Q4 is Talos-usable in text/tool-prompt mode with
`tools.native_calling:false`; native/default produced zero executable tool
calls. Do not treat this profile as native/default compatible unless later
evidence proves native/default tool-calling.

Configure Qwen:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
```

Configure Qwen plus the tested managed embedding profile:

```powershell
talos setup models --profile qwen2.5-coder-14b --embed-profile bge-m3 --server-path C:/path/to/llama-server.exe --write
```

`--embed-profile bge-m3` configures a separate local embedding-mode
`llama-server` on a separate port. It does not reuse the chat server for
embeddings.

Configure GPT-OSS:

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

Configure a Qwen3.6-VibeForged profile:

```powershell
talos setup models --profile qwen36vf-q6k --server-path C:/path/to/llama-server.exe --write
```

Configure DeepSeek-Coder-V2-Lite in its tested text/tool-prompt mode:

```powershell
talos setup models --profile deepseek-v2lite-q4km --server-path C:/path/to/llama-server.exe --write
```

## Switch Between Managed GGUF Profiles

Managed `llama.cpp` shows only the currently configured or running GGUF in
`/models`. Downloading a GGUF does not make it selectable by `/set model`.

To switch profiles, rewrite the active config and restart Talos:

```powershell
talos setup models --profile qwen36vf-q6k --server-path C:/path/to/llama-server.exe --write --force
```

`--force` creates a backup before replacing the existing config. After restart,
use `/models` to confirm the configured profile.

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

The managed `bge-m3` embedding profile uses the same cache root and is also
downloaded by llama.cpp when the embedding endpoint starts.

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
`/set model <backend/model>` to switch among visible active/catalog models.
For managed `llama.cpp`, use the setup workflow above to switch the configured
GGUF before relying on `/set model`.
