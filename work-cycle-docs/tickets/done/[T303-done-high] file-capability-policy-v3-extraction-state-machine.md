# T303 - File Capability Policy V3 Extraction State Machine

Status: done
Severity: high
Release gate: yes for document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

`FileCapabilityPolicy` now has extractable/deferred states for text-bearing PDF, DOCX, XLS, and XLSX when document extraction is enabled, while legacy `.doc`, PowerPoint, images, archives, compiled artifacts, and binaries remain unsupported/deferred. The remaining risk is not the first state-machine step; it is keeping dynamic extraction outcomes such as encrypted, OCR-required, corrupt, truncated, and adapter-missing consistent across every tool surface.

## Evidence from current code

- `FileCapabilityPolicy.Capability` includes extractable and deferred states as well as `UNSUPPORTED_BINARY_DOCUMENT`, `UNSUPPORTED_IMAGE_OR_SCAN`, and `UNKNOWN_TEXT_ATTEMPT_ALLOWED`.
- `UnsupportedDocumentFormats.isUnsupported(...)` delegates to the central capability policy instead of owning separate extension logic.
- Default RAG config excludes deferred/unsupported document/image/archive formats and lets explicit extraction policy decide PDF/DOCX/XLS/XLSX handling.

## Evidence from source crosscheck

Apache Tika, PDFBox, POI, and Tesseract show that some currently unsupported formats can become locally extractable, while others should remain skipped or require optional OCR/dependencies.

## User impact

Without richer states, Talos will either keep refusing implemented formats or loosen checks too broadly and accidentally treat unsupported/unsafe formats as readable.

## Product risk

High. Capability drift is a classic source of false claims: docs, tools, RAG, and final answers can disagree about what Talos can read.

## Runtime boundary affected

Read, grep, RAG includes/excludes, extraction adapters, docs, final-answer shaping, and release gates.

## Non-goals

- No parser implementation in this ticket.
- No archive extraction.

## Required behavior

Replace or extend binary unsupported checks with explicit states:

- `SUPPORTED_TEXT`
- `EXTRACTABLE_TEXT_DISABLED`
- `EXTRACTABLE_TEXT_ENABLED`
- `OCR_REQUIRED_DISABLED`
- `OCR_ENABLED`
- `DEFERRED_UNSUPPORTED`
- `ARCHIVE_UNSUPPORTED`
- `COMPILED_OR_EXECUTABLE_UNSUPPORTED`
- `UNKNOWN_TEXT_ATTEMPT_ALLOWED`
- `UNKNOWN_BINARY_SKIP`

The policy must answer:

- Can direct read extract this format?
- Can grep/search extract this format?
- Can RAG index this format?
- Is OCR required?
- Is the feature disabled by config?
- What user-facing limitation message should be shown?

Keep these separate:

- static capability: what Talos could attempt for a format under current config
- dynamic extraction outcome: what happened for one concrete file

Dynamic outcomes must include at least:

- `SUCCESS`
- `PARTIAL`
- `OCR_REQUIRED`
- `OCR_UNAVAILABLE`
- `PASSWORD_PROTECTED`
- `ENCRYPTED`
- `CORRUPT`
- `LIMIT_EXCEEDED`
- `FAILED`
- `BLOCKED_BY_PRIVACY`

## Proposed implementation

Create a V3 file capability model, possibly still under `dev.talos.core.ingest` or the new extraction package. Route `UnsupportedDocumentFormats` through the new policy for backwards-compatible messages while moving call sites toward explicit capability decisions.

Do not encode dynamic outcomes only as user-facing strings. They must be enum/status values that final-answer truthfulness, RAG indexing, docs tests, and live audit classification can assert.

## Tests

- `pdf_disabled_reports_extractable_but_disabled`
- `pdf_enabled_allows_extraction_policy`
- `pdf_enabled_but_encrypted_reports_dynamic_encrypted_outcome`
- `image_enabled_but_ocr_missing_reports_ocr_unavailable`
- `pptx_remains_deferred_unsupported_for_beta`
- `archive_remains_unsupported_and_not_recursed`
- `image_without_ocr_reports_ocr_required_disabled`
- `rag_includes_do_not_enable_extraction_without_policy`
- `read_grep_index_capability_decisions_are_consistent`

## Acceptance criteria

- No caller relies only on `isUnsupported(...)` for beta document formats.
- Docs and tool messages are generated from the same capability states.
- RAG cannot index a newly extractable format unless extraction policy explicitly enables it.

## Rollback / migration notes

Keep `UnsupportedDocumentFormats` as a compatibility facade until all callers move to the new state machine.

## Open questions

- Should feature flags live under `document_extraction` or under per-tool sections?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/core/index/Indexer.java`

## 2026-05-16 Implementation update

Evidence note: core state machine implemented for the current beta extraction formats; keep open for dynamic outcome expansion.

Implemented states include:

- extractable text enabled/disabled
- OCR enabled/disabled
- deferred unsupported
- archive unsupported
- compiled/executable unsupported
- unknown binary skip

Code evidence:

- `FileCapabilityPolicy` maps PDF, DOCX, XLS/XLSX, images, PowerPoint, archives, compiled artifacts, and binaries to explicit capability states.
- `EvidenceObligationPolicy` and `EvidenceGate` now use config-aware capability decisions.
- `ReadFileTool`, grep, slash grep, and RAG use the central policy instead of local extension-only rules.

Remaining blockers:

- Dynamic outcomes need more detail for encrypted/password-protected/corrupt/limit-exceeded cases.
- Docs/tests should eventually be generated from the policy to prevent drift.

## 2026-06-07 0.10.0 beta-scope reconciliation

Scope decision: PDF/DOCX/XLS/XLSX extraction is in scope for the `0.10.0` beta
decision, so T303 remains a document-beta release gate for consistent static
capability and dynamic outcome reporting. Image/OCR and PowerPoint remain
deferred unless a future ticket explicitly changes beta scope.

The next full audit should verify that beta-format extraction failures are
reported as concrete dynamic outcomes where available, not as generic
unsupported-format or guessed-content answers.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as deferred out of this main-merge line: future private-document / document-beta / v1 / future-capability scope, not current main-merge work.

Closed by Opus as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
