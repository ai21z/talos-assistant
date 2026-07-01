# T323 - Office Document Multi-Source Report Verification

Severity: High

Status: done - deterministic and installed-product live evidence now prove conservative multi-source office document report verification

Source: Five scenario big audit, 2026-05-19

## Problem

Talos has document extraction, but office-worker report generation is not verification-ready for valid PDF/DOCX/XLS/XLSX source reports.

The problem is not only extraction. The verifier and task contract do not yet enforce source coverage correctly.

## Evidence

Static audit originally found:

- source-derived verifier reads source evidence as text, not through document extraction;
- source-to-target parsing can capture one source where the user requests multiple sources;
- source-derived verification could pass aggregate overlap even if a generated report omitted one or more sources. The text-only verifier now checks each readable text source independently, but this ticket remains open because document-aware PDF/DOCX/XLS/XLSX source verification is not implemented.

Update 2026-05-20:

- `TaskContractResolverTest` already covers a natural office prompt that creates `office-summary.md` from `board-brief.pdf`, `client-notes.docx`, and `revenue.xlsx`, with the office summary as the only mutation output target and the three documents as source evidence targets.
- `StaticTaskVerifier` now reads extractable PDF/DOCX/XLS/XLSX source evidence through `DocumentExtractionService` during source-derived artifact verification.
- `StaticTaskVerifierTest` now covers a canonical PDF/DOCX/XLSX multi-source office summary that passes only when each extracted source contributes distinctive evidence.
- `StaticTaskVerifierTest` now covers omission of one extracted workbook source and verifies the failure cites the omitted source path without leaking the omitted source fact.
- Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --no-daemon
```

Both passed on `v0.9.0-beta-dev` after the implementation slice.

Exploratory live office case passed only weak text/CSV assertions:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221853/five-office-report-summary.txt
```

That pass is not enough to claim office-document readiness.

## Expected Behavior

For:

```text
Summarize q1.pdf, ops.docx, budget.xlsx, and legacy-sales.xls into office-summary.md.
```

Talos must:

- read or extract every source before writing the report,
- mark unsupported/corrupt/scanned sources honestly,
- create only supported text output unless a real binary writer exists,
- verify that each readable source contributes evidence to the output,
- fail verification if one readable source is omitted.

## Regression Tests

Add:

```text
multiSourceReportRequiresAllSources
validDocxSummaryUsesExtractedSourceEvidence                  // covered by combined canonical PDF/DOCX/XLSX test
validPdfSummaryUsesExtractedSourceEvidence                   // covered by combined canonical PDF/DOCX/XLSX test
validXlsxSummaryUsesExtractedSourceEvidence                  // covered by combined canonical PDF/DOCX/XLSX test
multiSourceReportFailsWhenOneSourceHasNoDistinctiveFacts     // covered for omitted extracted workbook evidence
corruptDocxCannotBeSummarizedWithoutGuessing
```

## Fix Direction

Implementation order:

1. Extend source-to-target artifact parsing to collect multiple source files.
2. Make source-derived verification document-aware through `DocumentExtractionService` or the same capability parser path as `read_file`.
3. Change source-derived verification from aggregate overlap to per-source coverage. This is implemented for readable text sources by the T307 slice; it still needs document-aware extraction coverage for this ticket.
4. Add private-mode artifact scan tests for document-source reports.

Current remaining work:

Closed evidence, 2026-05-20:

- `ToolCallLoopTest.sourceDerivedExactEvidenceWriteMissingSourcePhraseIsRepairedBeforeMutation` proves that exact-evidence source-derived writes with missing source phrases are replaced before approval with a conservative runtime evidence report.
- `ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite` proves compact mutation continuation includes source evidence readbacks and exact evidence requirements.
- `StaticTaskVerifierTest.sourceDerivedOfficeDocumentSummaryFailsWhenExactMarkersMaskUnsupportedProse` proves exact source markers alone cannot mask unsupported invented office prose.
- `StaticTaskVerifierTest.sourceDerivedOfficeDocumentSummaryPassesWhenEachExtractedSourceContributesDistinctiveFact` proves document-aware PDF/DOCX/XLSX source coverage can pass.
- Installed-product live audit:

```text
local/manual-testing/office-multisource-live-20260520-t323-r11/artifacts/TRANSCRIPT.txt
local/manual-testing/office-multisource-live-20260520-t323-r11/artifacts/office-summary.md
local/manual-workspaces/office-multisource-live-20260520-t323-r11/workspace
```

Live result:

```text
Status: COMPLETE
Outcome: COMPLETED_VERIFIED
Verification: PASSED - Source-derived artifact verification passed.
Action obligation: SOURCE_EVIDENCE_EXACT_COVERAGE (REPAIRED)
Approval: required=1 granted=1 denied=0
```

Artifact scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/office-multisource-live-20260520-t323-r11,local/manual-workspaces/office-multisource-live-20260520-t323-r11" --no-daemon
```

passed.

Important scope note: this closes the beta verifier/reliability blocker for conservative source-evidence office reports. It does not claim rich semantic office-document understanding, layout-perfect document analysis, OCR, comments/tracked-changes fidelity, workbook formula recalculation, or high-quality business prose generation. If richer semantic office summaries become a beta goal, open a separate product-quality ticket instead of reopening this verifier gate.
