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

