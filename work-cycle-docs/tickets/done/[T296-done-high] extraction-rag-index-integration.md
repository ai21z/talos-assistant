# T296 - Extraction RAG Index Integration

Status: done
Severity: high / P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Once PDF, Word, Excel, and image OCR text exist, RAG can index far more sensitive material. Existing index metadata and protected-path filters are good, but extraction introduces new derived text that needs policy versioning, source provenance, and private-mode controls.

## Evidence from current code

- `Indexer` writes policy metadata with privacy policy version, file capability policy version, RAG config hash, workspace hash, timestamp, and Talos version: `src/main/java/dev/talos/core/index/Indexer.java:380` through `:386`.
- `RagService.prepare(...)` blocks retrieval in private mode by default: `src/main/java/dev/talos/core/rag/RagService.java:113` through `:118`.
- `RagService.ensureIndexExists(...)` skips lazy indexing in private mode: `src/main/java/dev/talos/core/rag/RagService.java:304` through `:307`.
- Slash `/reindex` routes through `RagService.reindex(...)` and has private-mode tests.
- Top-level `rag-index` now routes through `RagService.reindex(...)`: `src/main/java/dev/talos/cli/launcher/RagIndexCmd.java:34`, `:42`.
- `Indexer.parseIndexableText(...)` now checks `PrivateDocumentPolicy.ragIndexAllowed(...)` before returning extracted text for indexing.
- Index metadata now includes privacy config hash, so changes to private-document RAG indexing opt-ins make prior indexes stale.
- `IndexingStats` now reports privacy skips separately from ordinary skips.

## Evidence from source crosscheck

Agent tool outputs and retrieval snippets can become model context. Indexes are durable artifacts and must be treated as privacy-sensitive.

## User impact

Private PDFs, DOCX files, spreadsheets, and OCR text could be indexed unexpectedly or served from stale indexes unless index policy is explicit and enforced.

## Product risk

High. RAG is a durable, cross-turn privacy boundary. Extraction turns previously skipped binary files into indexable text.

## Runtime boundary affected

RAG indexing, lazy indexing, slash `/reindex`, retrieve, dirty-index invalidation, vector embeddings, chunk metadata, and prompt context packing.

## Non-goals

- No vector database replacement.
- No encrypted index store in this ticket.

## Required behavior

- Extracted document text is indexed only when policy allows.
- Private mode blocks lazy and explicit reindex unless `privacy.rag.enabled_in_private_mode` or an explicit approval path allows it.
- Index metadata includes extraction policy version and extractor versions.
- Dirty indexes built before extraction policy changes rebuild or refuse.
- Chunks preserve extraction provenance: source file, format, page/sheet/cell/image metadata, partial status.

## Proposed implementation

Extend index metadata with `extractionPolicyVersion` and adapter version metadata before broad adapter rollout. Route all indexing through an extraction-aware pipeline:

`file path -> protected path check -> file capability/extraction policy -> extraction service -> sanitized extracted text -> chunk metadata -> LuceneStore`

Fix `/reindex` to call a mode-aware `RagService.reindex(...)` that enforces private-mode policy instead of exposing raw `Indexer` behavior.

This work should start before the format adapters are broadly enabled. Otherwise PDF/DOCX/XLSX/image adapters can ship with direct read support while RAG remains a second, delayed integration surface.

## Tests

- `private_mode_reindex_refuses_when_rag_disabled`
- `private_mode_reindex_allowed_only_with_explicit_config`
- `index_metadata_records_extraction_policy_version`
- `extraction_policy_version_change_rebuilds_or_refuses`
- `pdf_extracted_text_indexed_with_page_metadata`
- `xlsx_extracted_text_indexed_with_sheet_cell_metadata`
- `image_ocr_text_indexed_only_when_ocr_enabled`
- `dirty_index_with_old_extracted_canary_cannot_surface_raw_text`
- `reindex_uses_extraction_policy_before_adapter_output_is_indexed`
- `retrieval_citation_includes_document_page_or_sheet_provenance`

## Acceptance criteria

- `/reindex` behaves consistently with private mode.
- Extracted document text is never indexed through a path that bypasses privacy policy.
- Retrieval results cite extracted-document provenance accurately.
- The first enabled extraction adapter has RAG/index tests in the same feature pass, not a later cleanup pass.

## 2026-05-17 update

The top-level launcher bypass is fixed at the command path: `RagIndexCmd` now constructs `RagService` and calls `reindex(...)`, so private-mode RAG refusal is enforced by the same service used by slash commands. Regression test:

```text
dev.talos.cli.launcher.RagIndexCmdPrivateModeTest.rag_index_command_refuses_private_mode_when_rag_disabled
```

2026-05-17 second update:

`Indexer` now enforces private-document RAG indexing policy directly. The tests cover PDF, DOCX, and XLSX extraction in private mode with private-mode RAG enabled but `privacy.document_extraction.allow_rag_indexing=false`; the extracted private fact canaries are not written to the index. A policy-change regression also proves an index built while the opt-in was enabled becomes stale after the opt-in is disabled and rebuilds without private chunks.

Remaining work: chunk/citation provenance still needs richer page/sheet/cell metadata, and live-audit artifact evidence still needs to prove private-document fact canaries do not survive prompt-debug/provider-body/session/trace/log surfaces.

## 2026-06-07 0.10.0 beta-scope reconciliation

Scope decision: PDF/DOCX/XLS/XLSX text extraction is in scope for the `0.10.0`
beta decision. This keeps T296 as a release gate for extraction-backed RAG and
artifact safety. Image/OCR and PowerPoint remain outside current beta claims.

The next full audit should include PDF/DOCX/XLSX extraction prompts and
private-mode/model-handoff checks, but broader private-paperwork positioning
remains excluded until the private-folder release gates pass.

## Rollback / migration notes

Changing extraction/index metadata should force rebuild. If rebuild is unsafe or disabled in private mode, retrieval should refuse with a clear message.

## Open questions

- Should explicit `/reindex` in private mode ask for approval or refuse unless config enables it?

## Related files

- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/cli/repl/slash/ReindexCommand.java`
- `src/main/java/dev/talos/core/context/ContextPacker.java`

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by Opus as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
