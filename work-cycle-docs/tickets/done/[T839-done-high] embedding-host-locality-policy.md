# [T839-done-high] Embedding Host Locality Policy

Status: done
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 trust gap where embedding transports still use substring
localhost checks after T835 hardened chat transports. A configured embedding
host such as `http://127.0.0.1.evil.example:11434` must not be accepted as
local.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/reports/t835-chat-transport-localhost-guard.md`
- `src/main/java/dev/talos/core/embed/EmbeddingsClient.java`
- `src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java`

## Scope

- Reuse the URI-based locality policy introduced by T835 for embedding hosts.
- Apply default-deny locality enforcement to the Ollama embedding client and the
  OpenAI-compatible embedding client.
- Preserve explicit remote embedding opt-ins:
  - `ollama.allow_remote=true` for `EmbeddingsClient`.
  - `embed.allow_remote=true` for `CompatEmbeddingsClient`.
- Rename the T835 chat-specific policy owner if needed so the shared ownership is
  honest.

## Acceptance Criteria

- Lookalike loopback hosts such as `127.0.0.1.evil.example` are rejected by
  default for both embedding clients.
- Real loopback hosts continue to work.
- Explicit `allow_remote=true` continues to permit remote embedding hosts.
- Chat locality behavior remains unchanged.
- No `site/`, Qodana, candidate, release metadata, or unrelated Wave 6 fixes are
  touched.

## Implementation Evidence

Completed in implementation commit `071af4e74d377c4cb38df06e12d0775f09942887`
and closed after review.

- Added red tests proving both embedding clients accepted lookalike loopback
  hosts before the fix.
- Renamed `ChatHostLocalityPolicy` to neutral `HostLocalityPolicy`.
- Repointed chat providers and embedding clients to the shared URI-based
  locality policy.
- Removed substring localhost helpers from both embedding clients.
- Added `work-cycle-docs/reports/t839-embedding-host-locality-policy.md`.
- Focused embedding/locality/chat provider tests, all `core.embed.*` tests,
  full `check --no-daemon`, and `wikiEvidenceCloseGate --rerun-tasks
  --no-daemon` passed after the fix.
- Reviewer reran the focused locality/security/honesty set, full
  `check --no-daemon`, and `wikiEvidenceCloseGate --rerun-tasks --no-daemon`
  before closeout.

## Non-Goals

- Do not implement T834 redaction, T836 Windows path canonicalization, T837
  command-output handoff, or T838 master-key custody.
- Do not change embedding request/response payload behavior.
- Do not claim remote endpoints are safe; they are only explicit opt-ins.
