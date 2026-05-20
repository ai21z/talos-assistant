# T279 - Unsupported Format Final-Answer Truthfulness

Status: done - scripted final-answer truthfulness guard implemented
Severity: P0 for private-document beta
Release gate: no for unsupported/deferred format final-answer shaping; broader release audit remains gated by T280/T299/T301
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Even if read tools report unsupported formats honestly, the model can still answer as if it reviewed the document body.

## Evidence from current code

- `ExecutionOutcome` invokes `AssistantTurnExecutor.overrideUnsupportedDocumentClaimsIfNeeded`.
- Unsupported-family claim removal now catches spreadsheet/workbook-style compare claims.
- Search answers that say "No matches found" are corrected when grep skipped unsupported/binary files.

## Evidence from tests/audits

- `UnsupportedFinalAnswerTruthfulnessTest`
- 2026-05-20 focused command:

```text
.\gradlew.bat test --tests "dev.talos.cli.modes.UnsupportedFinalAnswerTruthfulnessTest" --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```

- Capability audit reports now record that unsupported/deferred overclaims are shaped at the runtime boundary, while image/OCR, PowerPoint, and broad private-document release claims stay out of beta scope.

## User impact

Users may trust a fabricated summary of PDFs, Word documents, spreadsheets, slide decks, images, archives, or binaries.

## Product risk

P0 for private-document beta and any claim of document-reader capability.

## Runtime boundary affected

Final-answer shaping after tool-loop unsupported read failures.

## Non-goals

- No actual PDF/Office/OCR extraction.

## Required behavior

If extraction did not happen, final answers must say so and avoid content claims.

## Proposed implementation

Keep expanding scripted and live prompt-bank coverage.

## Tests

- `unsupported_pdf_summary_does_not_fabricate`
- `unsupported_docx_summary_does_not_fabricate`
- `unsupported_xlsx_summary_does_not_fabricate`
- `unsupported_pptx_summary_does_not_fabricate`
- `unsupported_image_summary_does_not_fabricate`
- `unsupported_archive_summary_does_not_fabricate`
- `unsupported_binary_summary_does_not_fabricate`
- `unsupported_pdf_compare_to_text_reports_partial_only`
- `unsupported_xlsx_compare_to_text_reports_partial_only`
- `unsupported_image_compare_to_text_reports_partial_only`
- `unsupported_archive_search_does_not_claim_no_matches_without_skip_note`
- `unsupported_write_pdf_rejected_or_redirected_truthfully`
- `unsupported_create_docx_rejected_or_redirected_truthfully`

## Acceptance criteria

- Unsupported-format limitations survive bad model output.
- Live audit verifies this across the required format families.

## Rollback / migration notes

Stricter answer shaping may replace some model prose with capability notes.

## Open questions

- Should final-answer shaping be generalized into a reusable postcondition engine?

## Related files

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
