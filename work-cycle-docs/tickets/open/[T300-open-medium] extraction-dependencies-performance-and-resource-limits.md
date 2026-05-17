# T300 - Extraction Dependencies, Performance, and Resource Limits

Status: open
Severity: medium / high if extraction is enabled by default
Release gate: yes for beta-core PDF/DOCX/XLS/XLSX extraction; image/OCR is v1/open
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Document extraction can introduce large dependencies, high memory usage, parser crashes, and huge extracted outputs. Talos needs dependency and resource discipline before enabling document support. OCR remains v1/open because images are frozen out of beta.

## Evidence from current code

- Gradle dependencies now include PDFBox, Apache POI, and a Log4j-to-SLF4J bridge in addition to the existing Lucene, Jackson, SQLite, SLF4J/Logback, JLine, JavaFX, and JUnit stack: `build.gradle.kts`.
- JVM args are `-Xmx2g`: `gradle.properties`.
- `ReadFileTool` has a 2 MiB file-size cap and 16K output cap: `src/main/java/dev/talos/tools/impl/ReadFileTool.java:28`, `:30`.
- `GrepTool` skips files over 1 MiB: `src/main/java/dev/talos/tools/impl/GrepTool.java:33`, `:123`.
- `Indexer` uses virtual-thread tasks and configurable concurrency: `src/main/java/dev/talos/core/index/Indexer.java:291` through `:314`.

## Evidence from source crosscheck

Apache POI documents event-based Excel extractors for constrained memory footprints. OCR is dependency-sensitive and slow compared with text parsing, but image/OCR is not beta scope.

## User impact

Large PDFs or spreadsheets can freeze or degrade the local CLI if limits are not explicit. Image scans remain v1/open.

## Product risk

Medium to high. Performance failures look like broken Talos behavior and can corrupt user trust even without privacy leaks.

## Runtime boundary affected

Parser dependencies, build size, extraction timeouts, memory use, indexing throughput, CLI responsiveness, logs, and audit reproducibility.

## Non-goals

- No premature parser optimization before baseline correctness.
- No GPU OCR requirement.

## Required behavior

- Define per-format file size, page/sheet/cell/image dimension, extracted character, and timeout limits.
- Keep OCR dependency detection explicit for v1, but do not treat OCR as beta readiness evidence.
- Keep parser exceptions sanitized.
- Make extraction status explain partial/truncated output.
- Keep indexing concurrency bounded.

## Proposed implementation

Add config under a new `document_extraction` section:

- `enabled`
- `pdf.enabled`
- `word.enabled`
- `excel.enabled`
- `image_ocr.enabled`
- `max_file_bytes`
- `max_extracted_chars`
- `max_pages`
- `max_sheets`
- `max_cells`
- `ocr_timeout_ms`
- `parser_timeout_ms`

Add a `DocumentExtractionLimits` object and enforce it in the extraction service.

Dependency stance for beta:

- PDF: PDFBox direct adapter.
- DOCX/XLSX: Apache POI direct adapters.
- Images: external/local OCR provider adapter exists experimentally, but image/OCR is frozen for v1.
- Tika: do not use as the primary beta parser layer. It can be evaluated later for detection or compatibility after Talos has explicit format states, archive recursion denial, and extraction artifact tests.

Performance acceptance should use measurements from Windows developer machines, not only CI. Large spreadsheet tests should have separate "slow/manual" variants if they cannot stay inside normal `check` time. OCR performance tests belong to v1.

## Tests

- `large_pdf_truncates_with_partial_status`
- `large_xlsx_stops_at_cell_limit`
- `ocr_timeout_reports_partial_or_failed_status`
- `parser_exception_message_is_redacted`
- `extraction_limits_loaded_from_default_config`

## Acceptance criteria

- Extraction cannot exceed configured limits silently.
- Timeout/partial status is user-visible and audit-visible.
- Tests run within normal CI time.

## Rollback / migration notes

Keep extraction disabled by default until performance tests are stable on Windows developer machines.

## Open questions

- Should OCR be packaged as an external dependency check rather than a bundled binary?

## Related files

- `build.gradle.kts`
- `gradle.properties`
- `src/main/resources/config/default-config.yaml`
- `src/main/java/dev/talos/core/Config.java`
- `src/main/java/dev/talos/core/index/Indexer.java`

## 2026-05-16 Implementation update

Status: baseline dependencies, limits, and OCR command-resolution preflight implemented; beta-core performance hardening remains open. Image/OCR is frozen for v1.

Dependency evidence:

- PDFBox 3.0.7 added for PDF text extraction.
- Apache POI 5.5.1 added for DOCX/XLS/XLSX extraction.
- Log4j-to-SLF4J 2.25.4 added as runtime bridge so transitive Log4j API use does not print provider errors to the CLI.
- OCR remains external/configured and is not beta scope.
- `DocumentExtractionPreflight` and `/status --verbose` now expose whether Image OCR is disabled, unavailable, or backed by a resolved local command without running that command.
- The live-audit script can run `-UseRealOcr` later for v1 image/OCR work.

Runtime evidence:

- Extracted text is capped by `DocumentExtractionService`.
- Large workbook extraction now returns `PARTIAL` plus an `extraction-truncated` warning when the cap is hit.
- OCR command has timeout/output bounds.
- Full `./gradlew.bat clean check e2eTest --no-daemon` passed.
- Beta-core live audit `capability-live-audit-20260516-210854` passed after adding the logging bridge and explicit frozen image/PPT reporting; no Log4j provider error or stale PDFBox version appeared in that audit root.

Remaining blockers:

- Need large-file/page/sheet/cell performance tests beyond the current truncation regression.
- Need Windows performance measurement on realistic PDFs/workbooks.
- Need production OCR packaging/install decision and successful real-OCR audit later for v1.


