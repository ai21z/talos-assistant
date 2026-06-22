# 23 - Embedding Provider Architecture

**Status:** Current bounded reference
**Updated:** 2026-06-23
**Branch:** `v0.9.0-beta-dev`
**Scope:** RAG vector configuration, embedding transports, and opt-in managed
`llama.cpp` embedding work

---

## Current Boundary

Talos retrieval is local-first and Lucene-backed.

- BM25 lexical retrieval works without embeddings.
- Vector retrieval is optional.
- The shipped beta default is BM25-only:

```yaml
embed:
  provider: "disabled"
  model: "none"
  host: ""
  allow_remote: false

rag:
  vectors:
    enabled: false
```

This is deliberate. Managed `llama.cpp` chat is the default beta path. Managed
`llama.cpp` embeddings are available as an explicit opt-in path that starts a
separate embedding-mode `llama-server`; a fresh install remains BM25-only until
that profile is configured and the local embedding endpoint works.

## Implemented Transports

| Provider | Transport | Status |
| --- | --- | --- |
| `disabled` | `DisabledEmbeddings` | Default/BM25-only path. |
| `compat` | `CompatEmbeddingsClient` | OpenAI-compatible local `/v1/embeddings` endpoint. User must configure host/model. |
| `openai_compat` | `CompatEmbeddingsClient` | Alias for the same local-compatible endpoint shape. |
| `llama_cpp` | `CompatEmbeddingsClient` | OpenAI-compatible local `/v1/embeddings` endpoint. When `embed.managed.enabled=true`, Talos can start a separate managed embedding-mode `llama-server`; this is distinct from the chat server. |
| `ollama` | `EmbeddingsClient` | Explicit legacy Ollama embedding path. |

Unsupported providers fail clearly instead of silently falling back to another
transport.

## Production Call Sites

| Call site | Behavior |
| --- | --- |
| `Indexer.index()` | Resolves `EmbeddingProfile`, creates a document embedding client, and indexes vectors only when configured and working. |
| `RagService.prepare()` | Resolves `EmbeddingProfile`, creates a query embedding client, and skips the KNN lane when embeddings fail. |
| `talos doctor` / `/doctor` | Reports vector state, embedding provider/model/host locality, and whether retrieval is BM25-only or hybrid-if-embeddings-work. |

RAG is not a cloud search path and does not use a vector database.

## Built-In Profiles

`EmbeddingProfile` still defines known model profiles such as `bge-m3` and
`Qwen/Qwen3-Embedding-8B`. Those profiles describe vector-space parameters and
cache identity. They do not mean those models are the default.

The tested managed embedding setup profile is `bge-m3` with 1024 dimensions.
Setup can write it alongside a managed chat profile:

```powershell
talos setup models --profile qwen2.5-coder-14b --embed-profile bge-m3 --server-path C:/path/to/llama-server.exe --write
```

That writes `embed.provider: "llama_cpp"`, `rag.vectors.enabled: true`, and an
`embed.managed` block pointing at a dedicated embedding server. The embedding
server uses the same local `llama-server.exe` binary, but a separate port and a
separate embedding GGUF.

Explicit Ollama users can still select:

```yaml
embed:
  provider: "ollama"
  model: "bge-m3"
```

Explicit OpenAI-compatible local endpoint users can select:

```yaml
embed:
  provider: "compat"
  model: "<local-embedding-model>"
  host: "http://127.0.0.1:<port>"
  allow_remote: false

rag:
  vectors:
    enabled: true
```

## Deferred Work

| Gap | Status |
| --- | --- |
| Managed `llama.cpp` embeddings | Phase 1 implemented as an opt-in `embed.managed` path with a dedicated embedding-mode server. Still requires review and live smoke before broad beta claims. |
| Embedding model download/setup UX | Implemented for the tested `bge-m3` profile through `talos setup models --embed-profile bge-m3`; broader embedding profile catalog remains deferred. |
| Hybrid retrieval quality validation | Requires measured retrieval harness evidence, not just configuration. |
| Index/profile mismatch enforcement | Still needed: persist profile fingerprint in index metadata and warn/refuse incompatible reuse. |
| Multi-profile indexing | Not supported. One vector profile per workspace index. |

## Rules

1. Do not describe Ollama as required for default beta retrieval.
2. Do not claim vector retrieval is active unless `rag.vectors.enabled=true`
   and a local embedding endpoint succeeds.
3. Do not route core/tools callers through runtime policy just to get
   embedding behavior. The core embedding factory owns transport selection.
4. Keep Ollama as an explicit compatibility provider until removal is separately
   scoped.

This document supersedes the older frozen note that described the pre-compat
embedding state.
