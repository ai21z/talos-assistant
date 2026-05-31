# 23 — Embedding & Provider Architecture: Reference & Freeze

**Status:** FROZEN
**Date:** 2025-04-11
**Branch:** `v0.9.0-beta-dev`
**Scope:** Embedding profile abstraction, provider transport, vLLM roadmap

---

## Purpose

This document captures the current state of the embedding/provider architecture
work, records what was built, what was intentionally deferred, and defines the
frozen boundary. No further embedding or vLLM work should happen until V1
release unless explicitly unblocked.

---

## 1. What Was Built (PR1 — Merged)

### New classes

| Class | Package | Role |
|---|---|---|
| `EmbeddingProfile` | `core.embed` | First-class record capturing all vector-space-affecting parameters: provider, model, dimensions, instruction mode, query/document instructions, max input tokens, normalization. Includes `fingerprint()` and `cacheNamespace()`. |
| `EmbeddingsFactory` | `core.embed` | Static factory resolving `EmbeddingProfile` from config, constructing query and document embedding clients. Handles built-in profile defaults with config override semantics. |
| `InstructionEmbeddings` | `core.embed` | Decorator prepending instruction prefixes to text before delegating to raw transport. Used for instruction-aware models (e.g. Qwen3-Embedding-8B). Implements `BatchEmbeddings`. |

### Existing classes (unchanged in shape, rewired)

| Class | Change |
|---|---|
| `EmbeddingsClient` | Unchanged. Still the Ollama HTTP transport. Now created only via `EmbeddingsFactory.createRawClient()`. |
| `CachingEmbeddings` | Unchanged. Now receives `profile.cacheNamespace()` (= fingerprint) instead of legacy `"ollama/bge-m3"` string. |
| `BatchEmbeddings` | Unchanged interface. `InstructionEmbeddings` implements it. |
| `Embeddings` (SPI) | Unchanged interface. |

### Integration points (production code)

| Call site | What it does |
|---|---|
| `Indexer.index()` (line ~109) | `EmbeddingsFactory.profileFrom(cfg)` → `EmbeddingsFactory.forDocument(cfg)` → wraps in `CachingEmbeddings` with `profile.cacheNamespace()` |
| `RagService.prepare()` (line ~141) | `EmbeddingsFactory.profileFrom(cfg)` → `EmbeddingsFactory.forQuery(cfg)` → wraps in `CachingEmbeddings` with `"query/" + profile.cacheNamespace()` |

### Built-in profiles

| Constant | Provider | Model | Dims | Instruction-aware | Query instruction | Max tokens |
|---|---|---|---|---|---|---|
| `BGE_M3` | `ollama` | `bge-m3` | 1024 | No | — | 8192 |
| `QWEN3_EMBED_8B` | `ollama` | `Qwen/Qwen3-Embedding-8B` | 1024 | Yes | `"Instruct: Given a query, retrieve relevant passages that answer the query\nQuery: "` | 32768 |

### Config resolution order

```
embed.model  >  ollama.embed  >  "bge-m3" (default)
embed.provider  >  "ollama" (default)
```

When model name matches a built-in, the built-in provides **defaults** — not
unconditional overrides. Config keys for `provider`, `dimensions`,
`query_instruction`, `document_instruction`, `max_input_tokens`, and `normalize`
all take precedence over built-in values. If the resolved profile equals the
built-in exactly, the singleton instance is returned.

### Config keys (embed section)

```yaml
embed:
  model: "bge-m3"                    # or "Qwen/Qwen3-Embedding-8B", or custom
  provider: "ollama"                 # only "ollama" supported now
  dimensions: 1024                   # 0 = auto-detect
  query_instruction: "..."           # prefix for query embedding (trailing whitespace preserved)
  document_instruction: "..."        # prefix for document embedding
  max_input_tokens: 8192             # model's max input
  normalize: true                    # whether model outputs L2-normalized vectors
```

### Fail-fast behavior

`EmbeddingsFactory.createRawClient()` throws `UnsupportedOperationException`
if `profile.provider()` is anything other than `"ollama"`. This prevents
silent mismatch between profile identity and actual transport.

### Fingerprint & cache safety

- `fingerprint()` encodes: provider, model, dimensions, instruction mode,
  normalization flag, and a hash of instruction strings.
- `cacheNamespace()` delegates to `fingerprint()`.
- Changing any vector-space-affecting parameter changes the fingerprint →
  invalidates cache → forces re-embedding on next run.
- Legacy `"ollama/bge-m3"` cache keys become cold misses (one-time cost).

### Test coverage

| Test class | Tests | Covers |
|---|---|---|
| `EmbeddingProfileTest` | 17 | Built-in values, fingerprint determinism, fingerprint differentiation (provider/model/dims/instruction/normalization), cache namespace delegation, query-doc split detection, constructor validation |
| `EmbeddingsFactoryTest` | 19 | Default resolution, legacy key compat, model key precedence, Qwen built-in resolution, Qwen with provider/dimensions/instruction/multiple overrides, custom model, null config, query/document wrapping for bge-m3 vs instruction-aware, cache namespace, fail-fast for unsupported providers, profile resolution without transport |
| `InstructionEmbeddingsTest` | (exists) | Prefix prepending, batch delegation, null handling |

---

## 2. What Was Intentionally NOT Built

### Frozen — do not implement until explicitly unblocked

| Item | Reason for freeze |
|---|---|
| **vLLM transport** | Only Ollama runs on Windows. vLLM is Linux-only. Defer to post-V1 or Linux support phase. The `embed.provider` config key and fail-fast guard are ready for when transport is added. |
| **OpenAI-compatible transport** | Same as vLLM — the abstraction is ready (`createRawClient` switch point), but no implementation exists. |
| **Qwen3-Embedding-8B activation** | Built-in profile exists. `InstructionEmbeddings` wrapper exists. But Qwen3-Embedding-8B has not been tested end-to-end with Ollama on this codebase. Do not switch default model without retrieval quality validation. |
| **Index/profile mismatch enforcement** | The fingerprint exists but is not persisted in index metadata. Changing embedding model can silently reuse an incompatible index. Needs: store fingerprint at index creation, check on open, refuse or warn on mismatch. |
| **Multi-profile indexing** | One profile per workspace. No support for mixing embedding models in the same index. Correct for V1. |
| **Embedding dimension reduction (Matryoshka)** | Qwen3 supports it natively. Not implemented. Would require passing `dimensions` to the embedding API call, which Ollama may or may not support for a given model. |

---

## 3. Architecture Diagram (Current State)

```
Config (talos.yaml)
  │
  ├─ embed.model / embed.provider / embed.*
  │
  └──► EmbeddingsFactory
        │
        ├─ profileFrom(cfg) ──► EmbeddingProfile (record)
        │                         ├─ fingerprint()
        │                         ├─ cacheNamespace()
        │                         └─ requiresQueryDocumentSplit()
        │
        ├─ forQuery(cfg) ──► [InstructionEmbeddings?] ──► EmbeddingsClient (Ollama HTTP)
        │                                                    │
        └─ forDocument(cfg) ──► [InstructionEmbeddings?] ──► EmbeddingsClient (Ollama HTTP)
                                                              │
                                                         Ollama /api/embed
                                                              │
Call sites:                                                   │
  Indexer.index()  ─── forDocument ─── CachingEmbeddings ─────┘
  RagService.prepare() ─ forQuery ─── CachingEmbeddings ──────┘
```

### Extension point for future providers

```java
// EmbeddingsFactory.createRawClient() — current:
if (!"ollama".equals(profile.provider())) {
    throw new UnsupportedOperationException(...);
}
return new EmbeddingsClient(cfg);

// Future (when vLLM/OpenAI-compat transport is added):
return switch (profile.provider()) {
    case "ollama"       -> new EmbeddingsClient(cfg);
    case "vllm",
         "openai_compat" -> new OpenAiCompatEmbeddingsClient(cfg, profile);
    default             -> throw new UnsupportedOperationException(...);
};
```

---

## 4. Known Gaps to Address Later

| ID | Gap | Priority | Blocked by |
|---|---|---|---|
| E1 | **Index/profile mismatch detection** — persist fingerprint in index metadata, refuse reuse on change | High | Nothing (pure additive) |
| E2 | **vLLM / OpenAI-compatible transport** — add `OpenAiCompatEmbeddingsClient` | Post-V1 | Linux support / vLLM testing |
| E3 | **Qwen3 end-to-end validation** — test retrieval quality with Qwen3-Embedding-8B via Ollama | Medium | Ollama model availability, retrieval regression tests |
| E4 | **Matryoshka dimension reduction** — pass `dimensions` param to embedding API | Low | E3 (need Qwen3 working first) |
| E5 | **Default instruction tuning** — current Qwen3 query instruction is generic retrieval. May need domain-specific variants for code, docs, personal data. | Low | E3 |
| E6 | **CachingEmbeddings still uses `modelName` string** — should use profile fingerprint directly instead of caller passing the string | Low | Nothing (refactor) |

---

## 5. Rules for Unfreezing

Do NOT resume embedding/provider work unless:

1. V1 is released or release-blocked by an embedding issue
2. A specific retrieval quality problem is traced to bge-m3 limitations
3. Ollama adds Qwen3-Embedding-8B support that we can test locally
4. Linux/vLLM support becomes a release requirement

When unfreezing, start with **E1** (index/profile mismatch detection) before
switching any models. It is the safety gate that prevents silent corruption.

---

## 6. File Inventory

### Production code

| File | Lines | Status |
|---|---|---|
| `src/main/java/dev/talos/core/embed/EmbeddingProfile.java` | 126 | Complete, frozen |
| `src/main/java/dev/talos/core/embed/EmbeddingsFactory.java` | 158 | Complete, frozen |
| `src/main/java/dev/talos/core/embed/InstructionEmbeddings.java` | 58 | Complete, frozen |
| `src/main/java/dev/talos/core/embed/EmbeddingsClient.java` | 382 | Unchanged (Ollama transport) |
| `src/main/java/dev/talos/core/embed/CachingEmbeddings.java` | 121 | Unchanged (cache layer) |
| `src/main/java/dev/talos/core/embed/BatchEmbeddings.java` | 30 | Unchanged (interface) |
| `src/main/java/dev/talos/core/spi/Embeddings.java` | 10 | Unchanged (SPI) |

### Test code

| File | Tests | Status |
|---|---|---|
| `src/test/java/dev/talos/core/embed/EmbeddingProfileTest.java` | 17 | Complete, frozen |
| `src/test/java/dev/talos/core/embed/EmbeddingsFactoryTest.java` | 19 | Complete, frozen |
| `src/test/java/dev/talos/core/embed/InstructionEmbeddingsTest.java` | — | Complete, frozen |

---

## 7. Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2025-04-11 | Changed `QWEN3_EMBED_8B` built-in provider from `"vllm"` to `"ollama"` | vLLM frozen; Ollama is the only transport. Qwen3 built-in should not default to an unsupported provider. |
| 2025-04-11 | Fixed `profileFrom()` to treat built-ins as defaults, not unconditional replacements | Config overrides (provider, dimensions, instructions) were being silently ignored when model name matched a built-in. |
| 2025-04-11 | Froze all embedding/vLLM work | Architecture is in place. Further work is speculative without end-to-end validation. Focus on V1 release. |
| 2025-04-11 | Cache namespace = fingerprint (not `provider/model`) | Prevents stale vector reuse when any vector-space-affecting parameter changes. One-time cold-start cost on upgrade. |

---

*This document is the single source of truth for embedding architecture decisions.
Update it when unfreezing or making changes to `dev.talos.core.embed`.*
