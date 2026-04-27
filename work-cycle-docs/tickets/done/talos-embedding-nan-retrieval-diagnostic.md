# [done] Ticket: Embedding NaN Retrieval Diagnostic

Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `docs/new-architecture/23-embedding-provider-architecture.md`
- `work-cycle-docs/work-test-cycle.md`

## Why This Ticket Exists

Installed CLI verification on 2026-04-26 showed Ollama embedding calls failing
with:

```text
failed to encode response: json: unsupported value: NaN
No embedding returned from Ollama
```

Talos recovered by falling back to BM25-only retrieval and completed the turn
without crashing, but the transcript is noisy and retrieval produced no results.

## Problem

Embedding/provider work is currently frozen by the architecture docs unless V1
is release-blocked by an embedding issue. This failure is not a crash and is not
specific to the approval-discipline ticket that exposed it, but it can weaken
retrieval quality during installed manual verification.

## Goal

Diagnose whether the NaN response is caused by local Ollama/model state,
embedding-profile configuration, stale index metadata, or Talos request shape.
Keep any fix narrow and evidence-driven.

## Scope

### In scope

- Reproduce the failing embedding request outside the agent loop.
- Capture current model/profile/index configuration.
- Improve diagnostics if Talos cannot identify which embedding model/profile
  produced the NaN.
- Decide whether this is release-blocking.

### Out of scope

- Resuming broad embedding/provider architecture work before V1.
- Adding new embedding frameworks or cloud providers.
- Reworking retrieval ranking.

## Proposed Work

- Inspect the installed transcript and current config for embedding model/profile.
- Run a direct Ollama embedding probe with the same query text.
- Confirm whether the fallback path remains safe and user-visible enough.
- If the issue is environmental, document the setup fix.
- If the issue is Talos request/config shape, add a focused regression test and
  minimal guard.

## Likely Files / Areas

- `src/main/java/dev/talos/core/embed/EmbeddingsClient.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `docs/new-architecture/23-embedding-provider-architecture.md`
- local Ollama model/config state

## Test / Verification Plan

- Direct embedding probe against Ollama.
- Focused embedding client unit/integration test if the issue is in Talos code.
- Installed Talos manual run where retrieval either succeeds or degrades with a
  concise diagnostic and no crash.

## Acceptance Criteria

- The source of NaN embedding responses is identified.
- Talos either avoids the bad request shape or documents the local model/config
  fix.
- Retrieval fallback remains non-crashing.
- Transcript noise is reduced if the issue is actionable inside Talos.

## Completion Notes

Implemented on `ticket/talos-embedding-nan-retrieval-diagnostic`.

Diagnosis:

- Direct Ollama probe on `http://127.0.0.1:11434/api/embed` with `bge-m3`
  succeeds for `probe` with a 1024-dimensional vector.
- The selector-mismatch query
  `Check for mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript`
  reproducibly returns:
  `failed to encode response: json: unsupported value: NaN`.
- The same failure happens with `classes and IDs` wording, so this appears to be
  local Ollama/model behavior for specific text, not a Talos provider selection
  bug.

What changed:

- `EmbeddingsClient.embed(...)` now records every endpoint fallback attempt:
  endpoint, parameter shape, HTTP status/body preview, empty embedding result,
  invalid vector result, or exception class/message.
- When all attempts fail, the thrown `IllegalStateException` includes:
  model name, normalized input preview, and endpoint attempt details.
- Added `EmbeddingsClientDiagnosticTest` with an in-process HTTP server to pin
  the diagnostic behavior.

Verification:

- Direct Ollama probe reproduced the local `bge-m3` NaN response.
- `./gradlew.bat --no-daemon test --tests "dev.talos.core.embed.EmbeddingsClientDiagnosticTest" --tests "dev.talos.core.embed.*"`
- `./gradlew.bat --no-daemon test`
- `./gradlew.bat --no-daemon e2eTest`
- `./gradlew.bat --no-daemon check`
- Installed Talos uninstall/build/install/manual horror-synth run.

Manual result:

- Standard horror-synth run stayed safe: selector answer was grounded, denied
  edit stopped immediately, and playground files had no diff.
- Installed `rag-ask` reproduced the embedding failure and degraded to BM25-only
  without crashing. The warning now identifies `model 'bge-m3'`, the input
  preview, and all four endpoint fallback attempts.
