# T298 - Private Mode Reindex Policy Gate

Status: open
Severity: high / P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Private mode says RAG/retrieve is disabled by default, but slash `/reindex` can call the indexer directly. This is a runtime policy gap and becomes more serious when document extraction makes PDFs, DOCX files, XLSX files, and OCR text indexable.

## Evidence from current code

- Private-mode RAG toggle exists in `ProtectedReadScopePolicy.ragEnabledInPrivateMode(...)`: `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java:53`.
- `RagService.prepare(...)` blocks retrieval in private mode: `src/main/java/dev/talos/core/rag/RagService.java:113` through `:118`.
- `RagService.ensureIndexExists(...)` blocks lazy indexing in private mode: `src/main/java/dev/talos/core/rag/RagService.java:304` through `:307`.
- `ReindexCommand` bypasses those guards by calling `ctx.rag().getIndexer()` then `indexer.index(...)` or `indexer.reindex(...)`: `src/main/java/dev/talos/cli/repl/slash/ReindexCommand.java:39`, `:105`, `:107`.

## Evidence from tests/audits

Live prompt 18 showed inconsistent results:

- GPT-OSS reported private mode with RAG/retrieve disabled, then `/reindex` indexed chunks.
- Qwen reported private mode with RAG/retrieve disabled, then `/reindex` skipped all files.

The direct indexer call is sufficient evidence for a policy bug even before explaining the model-specific difference.

## User impact

A user can enable private mode and still trigger explicit indexing without the command enforcing the same private-mode rule.

## Product risk

P0 for private-document beta because indexing is durable and extraction will introduce more sensitive content.

## Runtime boundary affected

Slash command policy, RAG index creation, private mode, sensitive workspace handling, artifact scan, and live audit.

## Non-goals

- No index encryption.
- No broad RAG rewrite.

## Required behavior

- `/reindex` in private mode refuses by default or requires explicit opt-in/approval.
- The user-facing message must say private mode blocks indexing unless explicitly enabled.
- The command must not silently index extracted document text in private mode.

## Proposed implementation

Move reindex policy enforcement into `RagService.reindex(...)` and make `ReindexCommand` call that mode-aware method. If private mode disables RAG, return a clear `Result.Info` or `Result.Error` without calling `Indexer`.

## Tests

- `reindex_command_private_mode_refuses_when_rag_disabled`
- `reindex_command_private_mode_allows_when_explicitly_enabled`
- `reindex_command_private_mode_message_names_privacy_reason`
- `live_prompt_18_private_reindex_consistent_for_both_models`

## Acceptance criteria

- No code path from `/reindex` reaches `Indexer` in private mode unless policy explicitly allows it.
- Live audit prompt 18 becomes consistent.

## Rollback / migration notes

If users rely on `/reindex` in private folders, they can explicitly enable private-mode RAG after reading the warning.

## Open questions

- Should enabling private-mode RAG require config only, or can `/privacy` expose a separate explicit command?

## Related files

- `src/main/java/dev/talos/cli/repl/slash/ReindexCommand.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java`
