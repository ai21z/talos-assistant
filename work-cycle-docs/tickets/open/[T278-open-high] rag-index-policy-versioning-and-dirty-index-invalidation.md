# T278 - RAG Index Policy Versioning and Dirty Index Invalidation

Status: open - metadata V1 implemented, focused dirty-index integration added; live audit still needed
Severity: high
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Dirty historical RAG indexes may contain protected chunks. Retrieval-time sanitization is defense-in-depth, but old indexes should not be silently trusted.

## Evidence from current code

- `Indexer` writes `talos-index-metadata.json`.
- `RagService` rebuilds indexes with missing/stale policy metadata.

## Evidence from tests/audits

- `IndexerPolicyMetadataTest`
- `RagDirtyIndexIntegrationTest`

## User impact

Previously indexed private snippets can reappear after policy changes if old indexes are accepted silently.

## Product risk

High for developer beta; P0 for private-document beta.

## Runtime boundary affected

RAG index build, retrieval, dirty-index handling, provider-body/model context.

## Non-goals

- No index encryption in this ticket.

## Required behavior

New indexes write policy metadata. Missing/stale metadata triggers rebuild or refusal. Retrieval-time sanitization remains.

## Proposed implementation

Metadata V1 is implemented. This pass adds Lucene-backed dirty-index integration for missing metadata, old protected chunks, config-hash changes, and private-mode retrieval disablement. Add live prompt-bank coverage next.

## Tests

- `index_metadata_written_on_reindex`
- `index_missing_metadata_is_treated_dirty`
- `index_old_privacy_policy_version_is_dirty`
- `rag_missing_metadata_triggers_rebuild_and_removes_old_protected_chunks`
- `rag_config_hash_change_triggers_rebuild`
- `rag_private_mode_disables_lazy_indexing_by_default`

## Acceptance criteria

- No stale index silently serves raw snippets.
- User-facing message is clear when rebuild cannot happen.
- Private mode does not lazily build/retrieve by default.

## Rollback / migration notes

Policy-version changes can force rebuilds and may cost time on first retrieval.

## Open questions

- Should stale-index rebuild be automatic in all modes or refused in private mode?

## Related files

- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
