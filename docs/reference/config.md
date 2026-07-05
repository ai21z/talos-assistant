# Configuration

Talos user configuration lives under the Talos home directory, normally `~/.talos/config.yaml`.

Most users should write configuration through `talos setup wizard` on Ubuntu/WSL or `talos setup models` on Windows. Edit the YAML directly only when you need to review or repair a specific value.

Configuration covers:

- model backend and profile
- local llama.cpp server path
- local GGUF model path
- localhost or explicitly allowed remote engine host
- retrieval/index settings
- command profiles
- privacy mode

RAG in Talos means the local Lucene index and retrieval pipeline. BM25 retrieval can operate without vectors.

Vector retrieval requires a local embedding endpoint. No hosted vector service is needed.

Use:

```bash
talos status --verbose
```

to see which config file loaded and whether keys defaulted.

## Remote endpoints

Chat model endpoints are localhost-gated by default. A non-localhost backend host must be explicitly allowed for that backend. If remote use is allowed, full prompts can leave the machine.

## Retrieval

The local index is workspace-specific. Rebuild or refresh it when the workspace changes enough that old snippets would be misleading.
