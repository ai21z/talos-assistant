# T279 - Unsupported Format Final-Answer Truthfulness

Status: open - scripted tests added, live audit still required
Severity: P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Even if read tools report unsupported formats honestly, the model can still answer as if it reviewed the document body.

## Evidence from current code

- `ExecutionOutcome` invokes `AssistantTurnExecutor.overrideUnsupportedDocumentClaimsIfNeeded`.
- Unsupported-family claim removal now catches spreadsheet/workbook-style compare claims.

## Evidence from tests/audits

- `UnsupportedFinalAnswerTruthfulnessTest`

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

- `model_attempted_fabrication_is_overridden_by_runtime_postcondition`
- `unsupported_docx_compare_to_text_reports_partial_only`

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
