# T293 - Local Excel XLSX Extraction

Status: open
Severity: P0 for beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos currently refuses Excel workbooks. Beta requires Excel support, but spreadsheets need structured extraction, not lossy prose-only scraping.

## Evidence from current code

- `.xls` and `.xlsx` are unsupported in `FileCapabilityPolicy`: `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java:30`, `:31`.
- Current compare-flow honesty is tested for XLSX versus text: `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java:59`.
- `Indexer` filters unsupported formats before indexing: `src/main/java/dev/talos/core/index/Indexer.java:428`, `:453`.

## Evidence from source crosscheck

Apache POI documents Excel extractors for `.xls` and `.xlsx`, including lower-memory event-based extractors for constrained memory footprints.

## User impact

Users cannot ask Talos to inspect budgets, tax tables, invoice sheets, or project spreadsheets yet.

## Product risk

High. Spreadsheets often contain private financial and administrative data. Formula handling, hidden sheets, large workbooks, and cell coordinates must be explicit to avoid misleading summaries.

## Runtime boundary affected

Workbook extraction, search, RAG indexing, citations, model context, logs, traces, sessions, and final-answer truthfulness.

## Non-goals

- No workbook editing in beta.
- No formula recalculation unless a dedicated evaluator is introduced.
- No chart/image extraction in this ticket.
- No macro execution.

## Required behavior

- Extract visible workbook text from valid XLS/XLSX locally.
- Preserve sheet names and cell coordinates.
- Distinguish formula text from cached formula values if exposed.
- Report hidden sheets, unsupported features, truncation, and partial extraction.
- Enforce workbook size, sheet count, row/column, cell count, and timeout limits.
- Redact protected content before model context and artifacts.

## Proposed implementation

Implement an XLSX adapter behind T290's extraction interface. Use Apache POI. Prefer event-based or streaming APIs for large files when possible; workbook APIs are acceptable for small controlled fixtures but must remain bounded by limits. Convert extracted content into deterministic structured text such as:

```text
Sheet: Budget
A1: Category
B1: Amount
A2: Rent
B2: 1200
```

This format gives the model evidence without pretending Talos understands spreadsheet semantics beyond extracted cells.

Do not execute macros. Do not recalculate formulas unless a separate deterministic evaluator is introduced. If formula cells are exposed, state whether Talos is showing formula text, cached values, or both.

Beta scope decision: implement `.xlsx` first. Legacy `.xls`, macro-enabled `.xlsm`, and binary `.xlsb` should remain separate capability states unless they get dedicated tests. Do not market this as unrestricted Excel support while those formats are unsupported or partial.

## Tests

- `xlsx_extraction_reads_known_cells_with_coordinates`
- `xlsx_extraction_preserves_sheet_names`
- `xlsx_formula_cells_report_formula_and_cached_value_policy`
- `xlsx_macros_are_not_executed`
- `xlsx_chart_and_image_content_reports_unsupported`
- `xlsx_hidden_sheet_reports_warning`
- `xlsx_large_workbook_truncates_with_partial_status`
- `xlsx_extraction_redacts_secret_like_cells`
- `protected_xlsx_private_mode_does_not_enter_model_context`
- `xlsx_rag_indexing_uses_sanitized_structured_text_only`

## Acceptance criteria

- Valid XLSX workbook contents can be read, searched, cited, and indexed when allowed.
- Coordinates and sheet names appear in evidence.
- Formula and hidden-sheet limitations are visible in extraction metadata and final answers.
- No workbook extraction claim hides partial/truncated status.

## Rollback / migration notes

Keep `.xlsx` unsupported until the adapter passes tests. Legacy `.xls` may be a separate follow-up if beta scope accepts XLSX only.

## Open questions

- Should legacy `.xls`, `.xlsm`, and `.xlsb` be implemented before public beta, or should beta copy say "XLSX only"?
- Should formulas be shown as formulas, cached values, or both?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/core/context/ContextPacker.java`
- `src/main/java/dev/talos/core/index/Indexer.java`

## 2026-05-16 Implementation update

Status: implemented for visible cell text extraction from `.xls` and `.xlsx`; keep open for spreadsheet semantics hardening.

Code evidence:

- `DocumentExtractionService` extracts `.xls` through POI HSSF and `.xlsx` through POI XSSF.
- Extracted evidence includes sheet names and cell coordinates.
- Formula cells expose formula text plus cached display value when available; formulas are not recalculated.
- Hidden and very-hidden sheets are skipped and reported with an `excel-hidden-sheets` warning.
- Large extracted workbook output is capped with `PARTIAL` status and an `extraction-truncated` warning.
- Corrupt workbook files return `CORRUPT` and do not allow model handoff as evidence.
- `FileCapabilityPolicy` treats Excel formats as extractable when document extraction is enabled.
- RAG metadata includes document extraction policy version.

Verification:

- `DocumentExtractionAdaptersTest` passed, including hidden-sheet skip/warning coverage, formula/cached-value output, large-output truncation, and corrupt workbook `CORRUPT` coverage.
- `DocumentExtractionCanonicalFixturesTest` passed against a checked-in canonical `.xlsx` fixture and neighboring expected-text file.
- Full `./gradlew.bat clean check e2eTest --no-daemon` passed.
- Two-model beta-core live audit `capability-live-audit-20260516-195820` passed `07-xlsx-summary` and `10-compare-xlsx-text`.

Remaining blockers:

- No formula recalculation.
- Charts, macros, comments, password protection, deeper formula semantics, real-world large workbook performance, and `.xlsm`/`.xlsb` need explicit policy and fixtures.
- Do not claim generic Excel analysis; claim visible cell extraction only.


