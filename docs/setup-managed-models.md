# Managed Model Setup

Talos uses `llama_cpp` as the primary local model backend for the current beta
path. Ollama remains available as a legacy backend, but new local-agent setup
should prefer managed llama.cpp.

## Tested Profiles

The built-in setup profiles are the models used in current Talos audits:

| Profile | Hugging Face source | File / quant |
|---|---|---|
| `qwen2.5-coder-14b` | `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF` | `qwen2.5-coder-14b-instruct-q4_k_m.gguf` |
| `gpt-oss-20b` | `ggml-org/gpt-oss-20b-GGUF` | `gpt-oss-20b-mxfp4.gguf` |

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
