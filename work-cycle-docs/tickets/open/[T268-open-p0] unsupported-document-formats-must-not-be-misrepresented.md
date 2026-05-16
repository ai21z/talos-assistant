# T268 - Unsupported Document Formats Must Not Be Misrepresented

Status: open - implemented for tested extractable/deferred format paths; still open for broader private-document release gate
Severity: P0
Release gate: yes - private-document positioning and broad beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos previously had partial unsupported-format handling for direct PDF/Office reads and writes, but format truthfulness was not centralized across read, grep, slash grep, retrieve/RAG, summarize, compare, and final-answer behavior.

This work cycle added central format classification, local extraction for text PDFs/DOCX/XLS/XLSX, and integrated those paths into read/search/RAG. The ticket remains open because private-document positioning still requires broader real-world fixtures, and image/OCR plus PowerPoint are frozen for v1/open work.

## Evidence from current code

- `FileCapabilityPolicy` now distinguishes extractable text PDFs/DOCX/XLS/XLSX from deferred images/OCR, PowerPoint, archives, compiled/executable files, generic binary/data files, and unknown text-attempt files.
- `UnsupportedDocumentFormats` delegates to `FileCapabilityPolicy`, preserving the direct read/write boundary while broadening coverage.
- `GrepTool` and slash `GrepCommand` now skip/report unsupported and binary files.
- `Indexer` now uses code-level capability policy for protected/deferred/unsupported and extractable document paths, not config alone.
- `default-config.yaml` excludes protected paths plus unsupported Office/image/archive/binary extensions.
- Remaining gap: final-answer truthfulness for complex private-document summarize/compare flows still needs broader live/e2e scenario coverage.

## Evidence from external/source crosscheck

`work-cycle-docs/reports/t267-source-crosscheck.md` establishes that tool outputs are grounding evidence returned to the model. If extraction did not occur, Talos must not let final answers sound content-grounded.

## User impact

Users may believe Talos read a full PDF, Word document, spreadsheet, slide deck, image, archive, or binary when it only extracted text, saw the filename, skipped the file, or failed to extract content.

## Product risk

Release blocker for claims that Talos can read/summarize personal paperwork or arbitrary local files. False document review is a trust failure.

## Runtime boundary affected

- Read/extract/summarize/compare truthfulness
- Grep/search skipped-file reporting
- RAG corpus inclusion and retrieval
- Write/create format claims
- Final-answer claim validation

## Non-goals

- Full-fidelity PDF/Office/image/OCR/archive extraction before beta.
- Remote extraction by default.

## Required behavior

- Talos never claims to read or summarize unsupported/deferred content unless extraction actually occurred.
- Talos reports PDF/DOCX/XLS/XLSX extraction limitations instead of claiming full document review.
- It distinguishes filename-only inference from content evidence.
- Grep/search reports skipped unsupported/binary files when relevant.
- Retrieve/RAG does not index or surface unsupported binary contents.
- Write/create redirects unsupported binary formats to text/Markdown/HTML/CSV first.

## Proposed implementation

Implemented central `FileCapabilityPolicy` and routed `UnsupportedDocumentFormats`, grep, slash grep, RAG indexing, and default config through it.

Remaining implementation work:

- Add broader summarize/compare/final-answer scenarios that assert filename-only versus content-evidence wording.
- Keep image/OCR and PowerPoint as explicit v1/open issues without implying beta support.
- Keep documentation aligned with the unsupported-format boundary.

## Tests

- `unsupported_pdf_read_is_honest`
- `unsupported_docx_read_is_honest`
- `unsupported_xlsx_read_is_honest`
- `unsupported_pptx_read_is_honest`
- `unsupported_image_read_is_honest`
- `unsupported_archive_read_is_honest`
- `unsupported_binary_read_is_honest`
- `unsupported_binary_grep_skips_and_reports`
- `slash_grep_unsupported_binary_skips_and_reports`
- `unsupported_binary_retrieve_does_not_index_or_surface`
- `final_answer_does_not_claim_reviewed_unsupported_doc`

## Acceptance criteria

- Unsupported-format focused tests pass.
- Docs list supported and unsupported formats.
- Search/index paths disclose or exclude unsupported/binary files.
- Private-document release gate still requires broader final-answer scenario coverage.

## Rollback / migration notes

Users relying on accidental text reads of unknown extensions may see more cautious output. That is acceptable for beta trust.

## Open questions

- Which future local parsers should be allowed for PDF/Office/OCR, and what confidence metadata should they emit?

## Related files

- `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java`
- `src/main/java/dev/talos/core/ingest/ParserUtil.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java`
- `src/main/java/dev/talos/core/index/Indexer.java`

## 2026-05-15 hardening update

Additional implementation completed:

- Scripted final-answer tests now cover fabricated DOCX summaries and XLSX-vs-text compare claims.
- Runtime answer shaping removes unsupported-family claims such as spreadsheet/workbook content claims when extraction failed.
- PDF/DOCX/XLSX checked-in canonical fixtures now prove small text extraction independently of live-audit-generated fixtures.
- Large extracted output now reports `PARTIAL` plus an `extraction-truncated` warning instead of allowing complete-review language.

Still open:

- Broader live prompt-bank coverage for private-document PDFs/DOCX/XLS/XLSX, formula/truncation wording, PowerPoint refusal, image refusal/OCR-unavailable behavior, archive, binary, and unsupported write/create flows.
- Image/OCR and PowerPoint remain frozen for v1; archives/binaries remain unsupported.
