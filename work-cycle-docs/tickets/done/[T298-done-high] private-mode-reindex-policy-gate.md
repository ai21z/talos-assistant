# T298 - Private Mode Reindex Policy Gate

Status: done - private-mode reindex command paths and direct indexer private-document policy gate implemented and tested
Severity: high / P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Private mode says RAG/retrieve is disabled by default. Any explicit indexing command that calls the indexer directly bypasses that runtime policy and can create durable artifacts from private workspaces. This becomes more serious when document extraction makes PDFs, DOCX files, XLSX files, and OCR text indexable.

## Evidence from current code

- Private-mode RAG toggle exists in `ProtectedReadScopePolicy.ragEnabledInPrivateMode(...)`: `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java:53`.
- `RagService.prepare(...)` blocks retrieval in private mode: `src/main/java/dev/talos/core/rag/RagService.java:113` through `:118`.
- `RagService.ensureIndexExists(...)` blocks lazy indexing in private mode: `src/main/java/dev/talos/core/rag/RagService.java:304` through `:307`.
- Slash `/reindex` now uses `RagService.reindex(...)` and has private-mode coverage in `InfraCommandsTest`.
- Top-level `rag-index` now uses `RagService.reindex(...)`: `src/main/java/dev/talos/cli/launcher/RagIndexCmd.java:34`, `:42`.

## Evidence from tests/audits

Live prompt 18 showed inconsistent results:

- GPT-OSS reported private mode with RAG/retrieve disabled, then `/reindex` indexed chunks.
- Qwen reported private mode with RAG/retrieve disabled, then `/reindex` skipped all files.

The direct indexer call is sufficient evidence for a policy bug even before explaining the model-specific difference.

2026-05-17 focused regression:

- `dev.talos.cli.launcher.RagIndexCmdPrivateModeTest.rag_index_command_refuses_private_mode_when_rag_disabled`

The test first failed while `RagIndexCmd` called `Indexer` directly, then passed after routing the command through `RagService.reindex(...)`.

2026-05-17 follow-up:

- `IndexerPrivateDocumentPolicyTest` now proves the indexer itself refuses extracted PDF/DOCX/XLSX text in private mode when private-mode RAG is enabled but private-document RAG indexing is not explicitly allowed.
- Index metadata now hashes privacy config, preventing an index built under a more permissive document-extraction policy from remaining current after the opt-in is disabled.

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
- Top-level `rag-index` in private mode refuses by default or requires explicit opt-in/approval.
- The user-facing message must say private mode blocks indexing unless explicitly enabled.
- The command and the underlying indexer must not silently index extracted document text in private mode.

## Proposed implementation

Move reindex policy enforcement into `RagService.reindex(...)` and make every command path call that mode-aware method. If private mode disables RAG, return a clear message without calling `Indexer`.

## Tests

- `reindex_command_private_mode_refuses_when_rag_disabled`
- `rag_index_command_refuses_private_mode_when_rag_disabled`
- `reindex_command_private_mode_allows_when_explicitly_enabled`
- `reindex_command_private_mode_message_names_privacy_reason`
- `live_prompt_18_private_reindex_consistent_for_both_models`

## Acceptance criteria

- No code path from `/reindex` reaches `Indexer` in private mode unless policy explicitly allows it.
- No code path from top-level `rag-index` reaches `Indexer` in private mode unless policy explicitly allows it.
- No direct `Indexer` path indexes extracted private documents unless `PrivateDocumentPolicy.ragIndexAllowed(...)` allows it.
- Live audit prompt 18 becomes consistent.

## Rollback / migration notes

If users rely on `/reindex` in private folders, they can explicitly enable private-mode RAG after reading the warning.

## Open questions

- Should enabling private-mode RAG require config only, or can `/privacy` expose a separate explicit command?

## Related files

- `src/main/java/dev/talos/cli/repl/slash/ReindexCommand.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java`
