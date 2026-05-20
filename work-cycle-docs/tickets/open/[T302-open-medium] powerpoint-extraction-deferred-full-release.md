# T302 - PowerPoint Extraction Deferred to Full Release

Status: deferred-beyond-beta - PowerPoint extraction remains intentionally unsupported for current beta
Severity: medium
Release gate: no for beta if docs remain explicit; yes for full document-reader release
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

PowerPoint support is currently unsupported. Product direction allows PPT to wait until full release, but docs and extraction architecture must keep PPT honest and avoid accidental partial claims.

## Evidence from current code

- `.ppt` and `.pptx` are unsupported in `FileCapabilityPolicy`: `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java:32`, `:33`.
- Unsupported PPTX final-answer fabrication is tested: `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java:97`.

## Evidence from source crosscheck

Apache Tika and Apache POI can support presentation text extraction, but this is not required for the current beta bar.

## User impact

Users with slide decks must not be told Talos can inspect deck contents until a tested adapter exists.

## Product risk

Medium. PPT overclaim is less urgent than PDF/Word/Excel/image for beta, but false deck summaries would still damage trust.

## Runtime boundary affected

File capability policy, extraction service fallback, docs, final-answer truthfulness, RAG indexing.

## Non-goals

- No PPT extraction in beta.
- No slide rendering or image extraction in beta.

## Required behavior

- PPT/PPTX remain explicitly unsupported unless a full adapter is implemented.
- Search/RAG/final answers continue to disclose skipped PPT files.
- Document extraction architecture should allow a future PPT adapter without changing caller behavior.

## Proposed implementation

Keep PPT under the unsupported/deferred adapter in T290. Add future tests only when full-release PPT extraction is scheduled.

## Tests

- Existing `unsupported_pptx_summary_does_not_fabricate` remains.
- Future `pptx_text_extraction_reads_known_slide_text` when implemented.

## Acceptance criteria

- Beta docs say PPT is unsupported/deferred.
- No code path indexes or summarizes PPT content without an explicit adapter.

## Rollback / migration notes

None.

## Open questions

- Should PPT extraction reuse the Office adapter stack after DOCX/XLSX are stable?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java`
