# Model Setup

This page answers: "How do I configure Talos to use a local model?"

## Current Support

Talos uses configurable local model engines. The current beta path favors
managed llama.cpp. Ollama support remains available only when explicitly
selected as the backend.

Talos does not install model weights during installation. Model setup is a
separate step.

Local-first depends on the configured chat endpoint. Chat endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend. When remote chat is explicitly allowed, full prompts, including retrieved file contents, can be sent to that host.

## Guided Setup Wizard On Ubuntu/WSL x64

On Ubuntu/WSL x64, `talos setup wizard` is the guided setup path after the
source/developer installer:

```bash
talos setup wizard
```

The wizard keeps every side effect explicit:

- If no compatible `llama-server` is detected, it can offer the pinned CPU
  `llama.cpp` engine. It prints the upstream tag, asset name, download size,
  install directory, and SHA-256 before asking for confirmation.
- It offers only the accepted beta profiles `qwen2.5-coder-14b` and
  `gpt-oss-20b`.
- Before any model download, it prints the source, GGUF file name, approximate
  download size, disk/cache requirement, RAM guidance, CPU-only expectation,
  support level, cache path, and SHA-256.
- It writes `~/.talos/config.yaml` only after confirmation and backs up an
  existing config before overwrite.
- After config write, it asks whether to run `talos doctor --start`.

The Unix installer itself still installs Talos only. It does not silently
install system packages, llama.cpp, model weights, or config.

## Where to get `llama-server`

Recommended Ubuntu/WSL x64 path: use `talos setup wizard`. It can install the
Talos-pinned CPU engine after confirmation. The current pinned lane is the
official `ggml-org/llama.cpp` release `b9860`, asset
`llama-b9860-bin-ubuntu-x64.tar.gz`.

Direct/expert path: download a CPU asset for your OS from the
[official `ggml-org/llama.cpp` `b9860` release](https://github.com/ggml-org/llama.cpp/releases/tag/b9860).

On that page, use:

- `Ubuntu x64 (CPU)` for Linux x64, Ubuntu, or WSL x64. The extracted
  executable is `llama-server`.
- `Windows x64 (CPU)` for Windows x64. The extracted executable is
  `llama-server.exe`.

Talos does not claim arbitrary latest upstream builds are verified by Talos. If
you choose another release, a GPU asset, or a user-built binary, treat it as
user-provided: pass its path with `--server-path`, then prove it on your machine
with `talos doctor --start`.

## Show Setup Help

```powershell
talos setup models
```

This direct command prints the managed profile support levels and example
commands. Use it when you already have a compatible `llama-server` binary or a
user-owned GGUF path. On Ubuntu/WSL x64, prefer `talos setup wizard` for the
first local model setup. If you still need the server binary, start with
[Where to get `llama-server`](#where-to-get-llama-server).

## Managed Profile Support Levels

Accepted beta stability profiles are `qwen2.5-coder-14b` and `gpt-oss-20b`.

Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles, not beta stability baselines.

| Profile | Support level | Source | File | Tool mode | Guide |
| --- | --- | --- | --- | --- | --- |
| `qwen2.5-coder-14b` | accepted beta stability | `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF` | `qwen2.5-coder-14b-instruct-q4_k_m.gguf` | native/default | [guide](model-profiles/qwen2.5-coder-14b.md) |
| `gpt-oss-20b` | accepted beta stability | `ggml-org/gpt-oss-20b-GGUF` | `gpt-oss-20b-mxfp4.gguf` | native/default | [guide](model-profiles/gpt-oss-20b.md) |
| `qwen36vf-q4km` | experimental selectable | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf` | native/default | [guide](model-profiles/qwen36vf-q4km.md) |
| `qwen36vf-q6k` | experimental selectable | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf` | native/default | [guide](model-profiles/qwen36vf-q6k.md) |
| `deepseek-v2lite-q4km` | experimental selectable | `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF` | `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` | text/tool-prompt | [guide](model-profiles/deepseek-v2lite-q4km.md) |

Tool-mode evidence is per profile and quant. Qwen3.6-VibeForged Q4/Q6 passed
the initial Talos tool-call gate in native/default mode. DeepSeek-Coder-V2-Lite
Q4 is Talos-usable in text/tool-prompt mode with
`tools.native_calling:false`; native/default produced zero executable tool
calls. Do not treat this profile as native/default compatible unless later
evidence proves native/default tool-calling.

Use `talos doctor --start` after configuring and restarting Talos to prove the
selected profile loads and answers on the current machine. Save the
`talos doctor --start` output before calling a local profile setup verified.

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

`--server-path` must point to an existing local `llama-server` binary. Windows
paths usually end in `llama-server.exe`; Linux paths usually do not.

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

The Ubuntu/WSL setup wizard downloads accepted beta GGUF files to:

```text
~/.talos/models/gguf/<profile>/
```

The generated wizard config uses `model_path` so `talos doctor --start` can
prove the selected GGUF file exists before starting the managed server.

For direct `talos setup models` built-in managed Hugging Face profiles, Talos
configures the cache directory:

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
