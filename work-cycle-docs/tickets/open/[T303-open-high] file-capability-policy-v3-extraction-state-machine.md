# T303 - File Capability Policy V3 Extraction State Machine

Status: open
Severity: high
Release gate: yes for document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

`FileCapabilityPolicy` currently treats PDF, Word, Excel, PowerPoint, images, archives, compiled artifacts, and binaries as unsupported. That was correct for truthfulness, but it is too blunt once some formats become extractable. Talos needs a state machine, not a boolean unsupported check.

## Evidence from current code

- `FileCapabilityPolicy.Capability` has broad categories such as `UNSUPPORTED_BINARY_DOCUMENT`, `UNSUPPORTED_IMAGE_OR_SCAN`, and `UNKNOWN_TEXT_ATTEMPT_ALLOWED`.
- `UnsupportedDocumentFormats.isUnsupported(...)` is used by `ReadFileTool`, `GrepTool`, `ParserUtil`, and `Indexer` as a hard stop.
- Default RAG config excludes all current unsupported document/image/archive formats.

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

Status: core state machine implemented for the current beta extraction formats; keep open for dynamic outcome expansion.

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
