# T268 - Unsupported Document Formats Must Not Be Misrepresented

Status: open - implemented for tested format-classification/search/index paths; still open for broader private-document release gate
Severity: P0
Release gate: yes - private-document positioning and broad beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos previously had partial unsupported-format handling for direct PDF/Office reads and writes, but format truthfulness was not centralized across read, grep, slash grep, retrieve/RAG, summarize, compare, and final-answer behavior.

This work cycle added central format classification and integrated it into search and indexing paths. The ticket remains open because private-document positioning still requires broader final-answer scenario coverage and a future local extraction roadmap.

## Evidence from current code

- `FileCapabilityPolicy` now classifies PDF/Office, images/scans, archives, compiled/executable files, generic binary/data files, and unknown text-attempt files.
- `UnsupportedDocumentFormats` delegates to `FileCapabilityPolicy`, preserving the existing direct read/write boundary while broadening coverage.
- `GrepTool` and slash `GrepCommand` now skip/report unsupported and binary files.
- `Indexer` now excludes unsupported formats through code-level policy, not config alone.
- `default-config.yaml` excludes protected paths plus unsupported Office/image/archive/binary extensions.
- Remaining gap: final-answer truthfulness for complex summarize/compare flows still needs broader live/e2e scenario coverage.

## Evidence from external/source crosscheck

`work-cycle-docs/reports/t267-source-crosscheck.md` establishes that tool outputs are grounding evidence returned to the model. If extraction did not occur, Talos must not let final answers sound content-grounded.

## User impact

Users may believe Talos read a PDF, Word document, spreadsheet, slide deck, image, archive, or binary when it only saw the filename, skipped the file, or failed to extract content.

## Product risk

Release blocker for claims that Talos can read/summarize personal paperwork or arbitrary local files. False document review is a trust failure.

## Runtime boundary affected

- Read/extract/summarize/compare truthfulness
- Grep/search skipped-file reporting
- RAG corpus inclusion and retrieval
- Write/create format claims
- Final-answer claim validation

## Non-goals

- Full local PDF/Office/image/OCR/archive extraction before beta.
- Remote extraction by default.

## Required behavior

- Talos never claims to read or summarize unsupported content unless extraction actually occurred.
- It distinguishes filename-only inference from content evidence.
- Grep/search reports skipped unsupported/binary files when relevant.
- Retrieve/RAG does not index or surface unsupported binary contents.
- Write/create redirects unsupported binary formats to text/Markdown/HTML/CSV first.

## Proposed implementation

Implemented central `FileCapabilityPolicy` and routed `UnsupportedDocumentFormats`, grep, slash grep, RAG indexing, and default config through it.

Remaining implementation work:

- Add broader summarize/compare/final-answer scenarios that assert filename-only versus content-evidence wording.
- Design future local-only extraction tools for PDF/Office/images/OCR without implying current support.
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

Still open:

- Broader live prompt-bank coverage for PDF, PowerPoint, image, archive, binary, and unsupported write/create flows.
- No current local extraction support for PDF/Office/images/OCR/archive formats.
