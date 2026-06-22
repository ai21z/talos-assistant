# T856 Managed llama.cpp Embeddings

Status: open
Priority: high
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Opened from: T855/T857 Ollama-independence follow-up

## Problem

T855 and T857 closed the default chat/model-selection paths that could
surprisingly probe or spawn Ollama while managed `llama_cpp` is active. The
remaining independence gap is the vector retrieval lane.

Current truth:

- The beta default path is managed `llama_cpp` chat plus BM25-only retrieval
  unless a local embedding endpoint is explicitly configured.
- `llama_cpp` appears as a supported embedding provider through the compatible
  OpenAI-style embedding client, but Talos does not yet manage an embedding
  server as part of the managed `llama_cpp` setup.
- The managed chat `llama_cpp` engine does not implement embeddings itself:
  `LlamaCppEngine.embed(...)` throws unsupported.
- Therefore a user who wants hybrid/vector retrieval still needs a separately
  configured local embedding endpoint. That endpoint can be local and
  Ollama-free, but Talos does not yet make that path first-class.

This is a product-truth and beta-readiness gap: Talos should not imply vector
retrieval is ready through managed `llama_cpp` until the embedding server path
is configured, diagnosed, and tested.

## Evidence

- `src/main/java/dev/talos/engine/llamacpp/LlamaCppEngine.java`:
  `embed(...)` is unsupported for the chat engine.
- `src/main/java/dev/talos/core/embed/EmbeddingsFactory.java`: provider
  aliases include `compat`, `openai_compat`, and `llama_cpp`, which route to a
  compatible embedding HTTP endpoint.
- `docs/architecture/23-embedding-provider-architecture.md`: documents that
  the managed Talos `llama_cpp` chat server does not yet start with embeddings
  enabled.
- T855/T857 closeout: no default or user-driven chat/model path should touch
  Ollama unless active backend is `ollama` or the user explicitly qualifies an
  `ollama/` model.
- External (official llama.cpp `tools/server/README.md` + issue #8763, verified
  in the 2026-06-22 Ollama-independence audit): `llama-server`'s
  `--embedding`/`--embeddings` flag RESTRICTS the server to embeddings only and
  REFUSES chat/completions/infill. `POST /v1/embeddings` (OpenAI-compatible)
  requires a non-none pooling (`--pooling mean|cls|last`) with Euclidean
  normalization; native `POST /embedding` accepts all poolings. => one process
  CANNOT serve both chat and embeddings; managed embeddings REQUIRES a SEPARATE
  embedding-mode `llama-server` instance with a dedicated embedding GGUF.
- `src/main/java/dev/talos/engine/llamacpp/LlamaCppServerManager.java`
  `buildCommand()` builds the CHAT server command (serverPath, `-m`/`--hf-repo`,
  `-c`, `--host`, `--port`, `--alias`, server_args) and does NOT add
  `--embedding`/`--pooling`; the embedding server needs its own command/manager.
- `src/main/java/dev/talos/core/embed/EmbeddingProfile.java` already defines
  `BGE_M3` (1024-dim) and `QWEN3_EMBED_8B` built-ins with dims/instructions --
  reuse these for the managed embed-model profile metadata.
- `src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java` already POSTs
  `{model,input}` to `host + "/v1/embeddings"` with host-locality enforcement;
  the TRANSPORT is ready -- the gap is a MANAGED endpoint for it to talk to.
- 2026-06-22 model-eval: a chat-tuned GGUF cannot produce embeddings
  (`LlamaCppEngine.embed` throws; the chat server exposes no embedding endpoint),
  so the embedding model is a genuinely separate, dedicated-model concern.

## Goal

Make the Ollama-free vector lane first-class by adding managed `llama.cpp`
embedding-server support or an equivalent local-compatible embedding profile
that Talos can configure, diagnose, and document honestly.

## Scope

- Add a managed embedding profile path that starts or points to a local
  embeddings-compatible server without requiring Ollama.
- Keep the chat model server and embedding model/server separate unless the
  implementation proves that sharing a process is reliable for both APIs.
- Update setup/doctor/status surfaces so users can see:
  - embedding provider;
  - embedding model/profile;
  - endpoint locality;
  - dimension probe result;
  - retrieval mode: BM25-only or hybrid.
- Preserve BM25-only fallback when the embedding endpoint is absent or unhealthy.
- Update docs to say exactly what is managed and what remains user-configured.

## Non-Goals

- Do not remove Ollama as an explicit optional backend.
- Do not require a remote embedding service.
- Do not claim vector retrieval is always active.
- Do not replace Lucene/BM25 or introduce a vector database.
- Do not change ranking quality before the retrieval harness says it is needed.
- Do not touch `site/` in this ticket.

## Refined Implementation Approach (2026-06-22 session learnings)

The doc-supported, Ollama-free design is a SECOND managed `llama-server`
instance dedicated to embeddings, parallel to the chat server:

- Config: add a managed embedding-server block (e.g. `engines.llama_cpp.embed`
  or `embed.managed`) with `server_path` (reuse the chat binary), `model_path`
  OR `hf_repo`+`hf_file` for a dedicated embedding GGUF, a distinct `port`,
  `pooling` (default `mean`), and the model `dimensions`. Keep it OPT-IN: the
  shipped default stays BM25-only (T855); managed embeddings is a profile/flag
  the user enables.
- Lifecycle: start it via a managed process (a parallel `LlamaCppServerManager`
  instance or an embedding-aware variant) whose command adds `--embedding
  --pooling <type> -m <embed-gguf> --host 127.0.0.1 --port <embed-port>` and
  NONE of the chat flags (`--jinja`, chat-template, `--alias`). Health-probe it
  before use; tie its start/stop to the chat server lifecycle, but a failed or
  stopped embed server MUST NOT break chat.
- Wire the existing transport: set `embed.provider: "compat"` (or `llama_cpp`)
  and `embed.host` to the managed embed endpoint so `CompatEmbeddingsClient`
  targets the managed server (not a guessed/chat host). Keep its host-locality
  enforcement; fail closed on remote unless `embed.allow_remote`.
- Tested managed embed-model default: start with **`bge-m3`** (1024-dim,
  CPU-friendly, reuse `EmbeddingProfile.BGE_M3`); small enough to load ALONGSIDE
  the chat model (~+0.6-1 GB VRAM). Encode it as a tested managed embed profile
  (the T858 `--profile` pattern). Pick the exact GGUF repo/file and VERIFY it on
  disk (download + GGUF magic + size), not by assumption.
- Honest diagnostics (extend the T853/T854 active-backend-truth pattern):
  `doctor`/`/status`/`/context` must report embedding provider/model/host, the
  dimension-probe result, and retrieval mode (BM25-only vs hybrid) from the
  ACTIVE runtime, never config-stale. Absent/unhealthy endpoint or failed
  dim-probe -> report it and run BM25-only (Indexer/RagService already degrade;
  preserve that).
- Reindex guard: the Lucene index is built at a specific embedding dimension.
  Changing the embed model/dim REQUIRES a reindex -- detect a dim mismatch and
  tell the user to reindex rather than silently mixing dimensions.

## Suggested Phasing (keep the first increment reviewable)

- Phase 1 (shippable core): managed embed-server config + lifecycle (start a
  `--embedding` instance), wire `CompatEmbeddingsClient` at its host, bge-m3
  tested profile, honest BM25-vs-hybrid diagnostics, BM25-only fallback
  preserved, and the no-Ollama gate.
- Phase 2 (split to a follow-up ticket if it grows): embed-model profile
  catalog, reindex/dim-mismatch handling, and the deferred T853
  context-budget-meter reconciliation (the meter should reflect the active
  embedding state too).
- Process: trust-adjacent (privacy + diagnostic-truth surfaces). GPT implements,
  Opus verifies (code + tests + a live local-embedding smoke), no self-close.

## Acceptance Criteria

- A fresh managed `llama_cpp` beta setup can be configured for Ollama-free
  embeddings with a local endpoint.
- `talos doctor`, `/status --verbose`, or equivalent diagnostics report the
  vector lane honestly:
  - BM25-only when embeddings are absent/unhealthy;
  - hybrid only when a local embedding endpoint works and dimensions match.
- Remote embedding hosts remain rejected unless explicit `allow_remote=true` is
  configured.
- The managed path works without probing or spawning Ollama.
- Docs and honesty tests forbid claiming that vectors are active without a
  working local embedding endpoint.
- No-Ollama / no-embed-server gate: with no Ollama present and the managed embed
  server disabled or down, chat + `rag-index` + `rag-ask` + `/models` + doctor
  all work BM25-only with zero `:11434` contact and no embed-server error
  surfaced as a hard failure; with the managed embed server UP, index+query
  produce non-zero vectors and the KNN lane contributes (`knn>0`), still
  Ollama-free.
- Chat is never broken by the embedding server: stopping or failing the embed
  instance leaves chat fully functional.

## Suggested Tests

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.doctor.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.StatusCommandTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
```

Add focused tests proving:

- embedding endpoint locality is enforced;
- a healthy local embedding endpoint reports hybrid mode;
- an absent/unhealthy endpoint reports BM25-only fallback;
- no Ollama catalog or process is touched by the managed `llama_cpp` embedding
  path.

## Architecture Metadata

- Capability ownership: local retrieval / embeddings configuration and
  diagnostics.
- Operation type: setup/diagnostic/runtime retrieval support.
- Risk: medium-high; retrieval behavior and privacy boundary. Must fail closed
  on remote hosts and fall back honestly to BM25.
- Approval behavior: not applicable.
- Protected path behavior: retrieval index/privacy policy must remain
  unchanged.
- Checkpoint behavior: not applicable.
- Evidence obligation: focused embedding/doctor tests plus a live local
  embedding smoke when available.
- Verification profile: config, endpoint locality, dimension probe, and
  retrieval-mode diagnostics.
- Allowed refactor scope: embedding setup/config/diagnostics only.

