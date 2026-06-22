# 23 - Embedding Provider Architecture

**Status:** Current bounded reference
**Updated:** 2026-06-22
**Branch:** `v0.9.0-beta-dev`
**Scope:** RAG vector configuration, embedding transports, and deferred managed
embedding work

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

This is deliberate. Managed `llama.cpp` chat is the default beta path, but
managed `llama.cpp` embeddings are not wired yet. Until that work lands, Talos
must not imply that a fresh install has a working vector lane.

## Implemented Transports

| Provider | Transport | Status |
| --- | --- | --- |
| `disabled` | `DisabledEmbeddings` | Default/BM25-only path. |
| `compat` | `CompatEmbeddingsClient` | OpenAI-compatible local `/v1/embeddings` endpoint. User must configure host/model. |
| `openai_compat` | `CompatEmbeddingsClient` | Alias for the same local-compatible endpoint shape. |
| `llama_cpp` | `CompatEmbeddingsClient` | Alias for a local server exposing `/v1/embeddings`; the managed Talos `llama_cpp` chat server does not yet start with embeddings enabled. |
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
| Managed `llama.cpp` embeddings | Deferred to a separate ticket. Requires starting/probing a local embedding-capable server path without relying on Ollama. |
| Embedding model download/setup UX | Deferred. Do not claim automatic embedding setup until implemented. |
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
