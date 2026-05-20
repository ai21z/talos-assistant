# T323 - Office Document Multi-Source Report Verification

Severity: High

Status: implemented-awaiting-evidence - deterministic office document source-derived verification is implemented; live office audit and artifact evidence remain

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

1. Run a live office-worker audit with valid PDF/DOCX/XLSX fixtures, prompt-debug enabled, `/last trace` after every turn, and final workspace/report inspection.
2. Run artifact canary scanning over the live office audit directories.
3. Add corrupt/encrypted document truthfulness coverage if no existing deterministic test already proves the office-report final answer cannot guess from unreadable document sources.
4. Close or downgrade this ticket only after evidence proves the live office scenario is not just verifier-ready but behaviorally reliable.
