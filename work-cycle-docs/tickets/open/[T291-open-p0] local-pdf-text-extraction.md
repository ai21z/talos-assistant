# T291 - Local PDF Text Extraction

Status: open
Severity: P0 for beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos now has PDF text extraction, but PDF support must not weaken privacy, logging, trace, or RAG safety. Text PDFs and scanned/image-only PDFs must be distinguished.

## Evidence from current code

- PDF is classified as extractable when document extraction is enabled in `FileCapabilityPolicy`.
- `ReadFileTool`, grep/slash grep, and RAG indexing route PDFs through `DocumentExtractionService`.
- No-text PDFs return `OCR_REQUIRED` rather than successful empty extraction.
- Final-answer truthfulness tests include PDF fabrication prevention: `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java:90`.

## Evidence from source crosscheck

Apache PDFBox provides PDF text extraction command-line tooling. Apache Tika also lists Portable Document Format as a supported extraction family and uses PDF-oriented parsers.

## User impact

Users can ask Talos to summarize or search text-bearing PDFs. Scanned/image-only PDFs still require OCR and must be reported as not text-extracted.

## Product risk

High. PDFs often contain tax, legal, health, financial, and scanned personal documents. Raw extraction text can expose sensitive content into model context, logs, traces, prompt-debug, and RAG.

## Runtime boundary affected

PDF read, PDF search, PDF RAG indexing, extracted-text model handoff, prompt-debug, provider-body, trace/session persistence, and final answer.

## Non-goals

- No PDF editing.
- No guaranteed OCR of scanned PDFs in this ticket unless the OCR adapter is explicitly enabled and tested.
- No remote PDF parsing.

## Required behavior

- Extract text from valid text PDFs locally.
- Detect and report encrypted/password-protected/corrupt PDFs honestly.
- Distinguish text PDF extraction from scanned-image OCR requirements.
- Apply content redaction before model context and artifacts.
- Preserve page-level provenance where practical.
- Enforce file size, page count, character count, and timeout limits.

## Proposed implementation

Implement a PDF adapter behind T290's `DocumentExtractor` interface. Use direct PDFBox integration first unless a spike proves the dependency footprint or extraction behavior is unacceptable. Do not use a broad Tika parser as the first beta path for PDF because Talos needs narrow policy control before broad recursive parsing.

The adapter must not promise layout-perfect extraction. PDF text order can differ from visual order. The result must expose page-level provenance and warnings for partial/uncertain extraction.

## Tests

- `pdf_text_extraction_reads_known_text`
- `pdf_extraction_reports_page_count_and_partial_status`
- `pdf_extraction_reports_layout_order_limitations_when_detected`
- `pdf_extraction_redacts_secret_like_text`
- `protected_pdf_local_display_only_does_not_enter_model_context`
- `pdf_extraction_artifacts_do_not_contain_raw_canary`
- `encrypted_pdf_reports_unreadable_without_fabrication`
- `scanned_pdf_reports_ocr_required_when_ocr_disabled`
- `pdf_rag_indexing_uses_sanitized_extracted_text_only`

## Acceptance criteria

- Valid text PDF contents can be read and cited.
- Unsupported/failed PDF extraction never becomes a fabricated summary.
- PDF answers do not imply layout-perfect review when extraction is text-only.
- Artifact canary scan passes after PDF extraction tests.
- PDF extraction has deterministic unit, integration, and live-audit coverage.

## Rollback / migration notes

PDF adapter must be disable-able through config. If disabled, existing unsupported-format honesty remains.

## Open questions

- Should scanned PDF OCR be part of image OCR T294 or a separate PDF-OCR phase?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java`

## 2026-05-16 Implementation update

Status: implemented for small text PDFs; keep open for hardening.

Code evidence:

- `DocumentExtractionService` extracts PDFs through PDFBox and reports layout/order limitations.
- No-text/scanned-style PDFs return `OCR_REQUIRED` and do not allow model handoff as evidence.
- Encrypted PDFs return `ENCRYPTED` and do not allow model handoff as evidence.
- `gradle.properties` pins `pdfboxVersion=3.0.7`.
- Adapter provenance now reads the loaded implementation version instead of using a hardcoded string.
- `ReadFileTool`, grep, slash grep, and RAG indexing route through extraction-aware policy.

Verification:

- `DocumentExtractionAdaptersTest` passed, including no-text PDF `OCR_REQUIRED` and encrypted PDF `ENCRYPTED`.
- `ReadFileToolTest` and `GrepToolTest` passed, including no-text PDF user-facing behavior.
- Full `./gradlew.bat clean check e2eTest --no-daemon` passed.
- Two-model beta-core live audit `capability-live-audit-20260516-210854` passed `05-pdf-summary`.

Remaining blockers:

- Scanned PDFs are not solved by PDFBox text extraction; they are now truthfully reported as OCR-required.
- Corrupt, large, and layout-heavy PDFs need more fixtures.
- Private-document positioning remains forbidden.


