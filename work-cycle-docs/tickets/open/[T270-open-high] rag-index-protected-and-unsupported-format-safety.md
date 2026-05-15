# T270 - RAG Index Protected and Unsupported Format Safety

Status: open
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

RAG defaults can index `.env`-like files, and code-level indexing does not independently enforce protected-path or unsupported-format exclusion.

## Evidence from current code

- `default-config.yaml` includes `**/*.env`.
- Protected excludes for `.env`, `.env.*`, `secrets/**`, `.ssh/**`, `.aws/**`, `.azure/**`, `.gnupg/**`, `.config/gcloud/**`, and `protected/**` are missing.
- `Indexer.createFileFilter(...)` relies on configured include/exclude globs.
- `RagService.prepare(...)` reads existing index snippets and returns them to `RetrieveTool`.

## Evidence from external/source crosscheck

Gemini and Codex both use policy/sandbox concepts as runtime gates. Config is useful, but Talos needs code-level enforcement to protect against config drift and dirty indexes.

## User impact

Private data may be indexed once and later surfaced by unrelated retrieval prompts.

## Product risk

Release blocker for private folders until retrieval-time sanitization and index exclusion pass.

## Runtime boundary affected

RAG indexing, RAG retrieval, dirty-index handling, provider-body/model context.

## Non-goals

- Full index encryption.
- Remote retrieval.

## Required behavior

- Default config excludes protected paths.
- Indexer applies code-level protected/unsupported filtering.
- Retrieval sanitizes snippets even from dirty old indexes.
- Output notes when snippets were omitted/redacted.

## Proposed implementation

Use `ProtectedContentPolicy` and `FileCapabilityPolicy` in `Indexer`, `RagService`, and `RetrieveTool`. Update default config and consider index versioning or invalidation.

## Tests

- `retrieve_does_not_leak_env_canary`
- `retrieve_does_not_leak_dirty_index_canary`
- `unsupported_binary_retrieve_does_not_index_or_surface`
- default config protected-exclude test

## Acceptance criteria

- Protected and unsupported files are not indexed by default.
- Dirty indexes cannot leak raw canaries.

## Rollback / migration notes

Index invalidation can be disruptive. If deferred, retrieval-time sanitization is mandatory.

## Open questions

- Should Talos delete/rebuild existing indexes when the policy version changes?

## Related files

- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/tools/impl/RetrieveTool.java`
- `src/main/resources/config/default-config.yaml`

## 2026-05-15 hardening update

Implemented:

- `ProtectedContentPolicy.POLICY_VERSION`
- `FileCapabilityPolicy.POLICY_VERSION`
- RAG index metadata file: `talos-index-metadata.json`
- metadata fields for schema, privacy policy, file-capability policy, RAG config hash, workspace root hash, creation time, and Talos version
- stale/missing-policy metadata detection
- rebuild-before-retrieval behavior in `RagService`

Still open:

- Broader tests for old protected chunks, config-hash changes, and rebuild failure modes.
- User-facing stale-index message when automatic rebuild is not possible.
