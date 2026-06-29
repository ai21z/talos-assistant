# Managed Model Setup

Talos uses `llama_cpp` as the primary local model backend for the current beta
path. Ollama remains available as a legacy backend, but new local-agent setup
should prefer managed llama.cpp.

Local-first boundary: Chat endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend. When remote chat is explicitly allowed, Talos can send full prompts, including retrieved file contents, to that host. Keep the chat host on `127.0.0.1`, `::1`, or `localhost` to remain local-first.

## Profile Support Levels

Accepted beta stability profiles are `qwen2.5-coder-14b` and `gpt-oss-20b`.

Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles, not beta stability baselines.

The built-in setup profiles are:

| Profile | Support level | Hugging Face source | File / quant | Tool mode | Guide |
|---|---|---|---|---|---|
| `qwen2.5-coder-14b` | accepted beta stability | `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF` | `qwen2.5-coder-14b-instruct-q4_k_m.gguf` | native/default | [guide](user/model-profiles/qwen2.5-coder-14b.md) |
| `gpt-oss-20b` | accepted beta stability | `ggml-org/gpt-oss-20b-GGUF` | `gpt-oss-20b-mxfp4.gguf` | native/default | [guide](user/model-profiles/gpt-oss-20b.md) |
| `qwen36vf-q4km` | experimental selectable | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf` | native/default | [guide](user/model-profiles/qwen36vf-q4km.md) |
| `qwen36vf-q6k` | experimental selectable | `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF` | `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf` | native/default | [guide](user/model-profiles/qwen36vf-q6k.md) |
| `deepseek-v2lite-q4km` | experimental selectable | `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF` | `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf` | text/tool-prompt | [guide](user/model-profiles/deepseek-v2lite-q4km.md) |

Tool-mode evidence is per profile and quant. Qwen3.6-VibeForged Q4/Q6 passed
the initial Talos tool-call gate in native/default mode. DeepSeek-Coder-V2-Lite
Q4 is Talos-usable in text/tool-prompt mode with
`tools.native_calling:false`; native/default produced zero executable tool
calls. Do not treat this profile as native/default compatible unless later
evidence proves native/default tool-calling.

Use `talos doctor --start` after configuring and restarting Talos to prove the
selected profile loads and answers on the current machine. Save the
`talos doctor --start` output before calling a local profile setup verified.

Primary references:

- llama.cpp Hugging Face loading: <https://github.com/ggml-org/llama.cpp#obtaining-and-quantizing-models>
- Qwen profile source: <https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF>
- GPT-OSS profile source: <https://huggingface.co/ggml-org/gpt-oss-20b-GGUF>
- Hugging Face cache behavior: <https://huggingface.co/docs/hub/local-cache>

## Talos-Owned Model Cache

Run:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
```

or:

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

For experimental selectable profiles:

```powershell
talos setup models --profile qwen36vf-q6k --server-path C:/path/to/llama-server.exe --write
talos setup models --profile deepseek-v2lite-q4km --server-path C:/path/to/llama-server.exe --write
```

## Switching Managed GGUF Profiles

Managed `llama.cpp` exposes the currently configured or running GGUF. `/models`
does not scan every downloaded Hugging Face cache entry, and `/set model
llama_cpp/<name>` cannot hot-swap a downloaded-but-unconfigured GGUF.

To switch from one managed profile to another, rewrite the active config and
restart Talos:

```powershell
talos setup models --profile qwen36vf-q6k --server-path C:/path/to/llama-server.exe --write --force
```

`--force` writes a backup beside the existing config before replacing it. After
restart, `/models` should show the newly configured managed `llama.cpp` model.

The generated config sets:

```yaml
engines:
  llama_cpp:
    hf_repo: "..."
    hf_file: "..."
    hf_cache_dir: "C:/Users/<user>/.talos/models/huggingface"
```

At managed server launch, Talos sets `HF_HOME` to that `hf_cache_dir`. llama.cpp
then downloads and caches Hugging Face model files under the Talos home folder.

## User-Owned GGUF File

Users who already keep model files elsewhere can configure a direct model path:

```powershell
talos setup models --profile my-agent --server-path C:/path/to/llama-server.exe --model-path D:/models/agent.gguf --write
```

That writes `model_path` and leaves `hf_repo` / `hf_file` blank.

## Windows YAML Discipline

Generated config uses forward-slash paths because double-quoted YAML treats
backslash as an escape prefix. Hand-written Windows paths should either use
forward slashes:

```yaml
server_path: "C:/Users/me/talos/llama-server.exe"
```

or single quotes:

```yaml
server_path: 'C:\Users\me\talos\llama-server.exe'
```

If the user config is malformed, `talos status --verbose` reports the config
path and parse error instead of silently falling back to defaults.
