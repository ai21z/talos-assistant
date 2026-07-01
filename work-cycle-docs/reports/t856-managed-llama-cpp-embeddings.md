# T856 Managed llama.cpp Embeddings

Status: implemented-awaiting-review
Date: 2026-06-23
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation commit: containing commit for this report

## Summary

T856 implements the first managed, Ollama-free vector lane for Talos.

The beta default remains BM25-only. Users opt into managed embeddings through a
setup profile or equivalent config. When enabled, Talos starts a second local
`llama-server` instance in embedding mode and points the existing
OpenAI-compatible embedding transport at that endpoint.

This is intentionally separate from the managed chat server. `llama-server`
embedding mode is not treated as a shared chat-plus-embedding process.

Pass 2 fixes the review-blocking lifecycle issue found after the first
implementation: query-time retrieval creates short-lived embedding clients, but
the managed embedding server is now owned by a shared registry instead of each
client. Closing a transient query client no longer stops the local embedding
server after every query. The registry owns deterministic shutdown.

## Code Changes

- `src/main/java/dev/talos/core/embed/ManagedLlamaCppEmbeddingConfig.java`
  - Adds a config view for `embed.managed`.
  - Reads server path, model path or Hugging Face source, cache directory,
    listen host, port, pooling mode, and server args.
- `src/main/java/dev/talos/core/embed/ManagedLlamaCppEmbeddingServerManager.java`
  - Starts a dedicated embedding-mode `llama-server`.
  - Builds commands with `--embedding`, `--pooling`, `--host`, and `--port`.
  - Uses either `-m <model_path>` or `--hf-repo` / `--hf-file`.
  - Excludes chat command flags such as `--jinja`, chat-template flags, and
    `--alias`.
  - Writes a bounded local lifecycle log under `.talos/logs`.
  - Stops an already-running but not-ready managed process if readiness fails,
    matching the cold-start cleanup path.
- `src/main/java/dev/talos/core/embed/ManagedEmbeddingEndpointRegistry.java`
  - Owns shared managed embedding endpoints across short-lived clients.
  - Returns no-op-close leases to transient clients.
  - Provides explicit `closeAll()` ownership and a JVM shutdown hook for the
    production registry.
- `src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java`
  - Uses the managed endpoint when `embed.host` is blank and
    `embed.managed.enabled=true`.
  - Starts the managed endpoint before embedding requests.
  - Keeps host-locality enforcement.
  - Uses the shared registry for normal managed config and keeps direct injected
    endpoints client-owned for focused tests.
- `src/main/java/dev/talos/core/embed/CachingEmbeddings.java`
  - Still closes AutoCloseable delegates and cache handles, but registry-backed
    managed endpoint leases are no-op-close so query-time try-with-resources
    does not stop the shared process.
- `src/main/java/dev/talos/core/embed/InstructionEmbeddings.java`
  - Closes AutoCloseable delegates so future instruction-wrapped managed
    endpoints do not leak.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
  - Adds `--embed-profile` and `--embed-port`.
  - Adds tested `bge-m3` managed embedding profile.
- `src/main/java/dev/talos/cli/launcher/DiagnoseCmd.java`
  - Closes the embedding probe client.
- `src/main/java/dev/talos/cli/doctor/RetrievalStateProbe.java`
  - Reports the managed embedding host when configured.
- `src/main/java/dev/talos/cli/repl/slash/StatusCommand.java`
  - Adds verbose `EmbedHost` and `Retrieval` rows.

## Setup Profile

The initial tested embedding profile is:

| Profile | Source | File | Dimensions | Pooling |
| --- | --- | --- | --- | --- |
| `bge-m3` | `ggml-org/bge-m3-Q8_0-GGUF` | `bge-m3-q8_0.gguf` | `1024` | `mean` |

Example:

```powershell
talos setup models --profile qwen2.5-coder-14b --embed-profile bge-m3 --server-path C:/path/to/llama-server.exe --write
```

## Bounded Claims

- Default retrieval remains BM25-only.
- Managed embeddings are opt-in.
- Hybrid retrieval is available only when the local embedding endpoint starts
  and returns usable vectors.
- The implementation does not claim measured retrieval-quality improvement.
- The implementation does not add a vector database.
- The implementation does not remove Ollama as an explicit optional backend.
- The implementation does not change private-mode indexing policy.

## Tests Added Or Extended

- `ManagedLlamaCppEmbeddingServerManagerTest`
  - pins embedding-mode command construction;
  - pins Hugging Face source and `HF_HOME`;
  - pins absence of chat command flags;
  - pins cleanup when an already-running process fails readiness.
- `CompatEmbeddingsClientTest`
  - proves managed endpoint startup happens before the first compatible
    embedding request;
  - proves short-lived managed clients share one endpoint owner and registry
    shutdown stops it deterministically.
- `SetupCmdTest`
  - pins the generated `bge-m3` managed embedding YAML;
  - pins the no-embed-profile default as BM25-only (`provider=disabled`,
    `model=none`, no managed block, vectors disabled).
- `DoctorProbesTest`
  - pins the managed embedding host in retrieval diagnostics.
- `InfraCommandsTest`
  - pins `/status --verbose` embedding host and retrieval-mode wording.
- `InstructionEmbeddingsTest`
  - pins delegate close propagation.

## Review Status

Pass 2 adds deterministic lifecycle coverage for the review blocker.

Local verification after pass 2:

- `.\gradlew.bat test --tests "dev.talos.core.embed.*" --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon`
  - pass.
- `.\gradlew.bat check --no-daemon`
  - pass.
- `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon`
  - pass.
- `git diff --check -- . ':!site'`
  - clean.

T856 remains open for review and live local-embedding smoke. The live smoke
should prove an Ollama-free index/query path where vectors are generated, the
KNN lane contributes, and no `:11434` contact occurs.

## Deferred Work

- A broader embedding-profile catalog.
- Persistent index/profile mismatch detection and reindex guidance.
- A richer setup UI for already-downloaded embedding GGUFs.
- Measured retrieval-quality improvement work, which belongs behind the T847
  gold-context harness.
