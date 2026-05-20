# T323 - Office Document Multi-Source Report Verification

Severity: High

Status: still-open - office document multi-source report verification remains a current blocker

Source: Five scenario big audit, 2026-05-19

## Problem

Talos has document extraction, but office-worker report generation is not verification-ready for valid PDF/DOCX/XLS/XLSX source reports.

The problem is not only extraction. The verifier and task contract do not yet enforce source coverage correctly.

## Evidence

Static audit found:

- source-derived verifier reads source evidence as text, not through document extraction;
- source-to-target parsing can capture one source where the user requests multiple sources;
- source-derived verification could pass aggregate overlap even if a generated report omitted one or more sources. The text-only verifier now checks each readable text source independently, but this ticket remains open because document-aware PDF/DOCX/XLS/XLSX source verification is not implemented.

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
validDocxSummaryUsesExtractedSourceEvidence
validPdfSummaryUsesExtractedSourceEvidence
validXlsxSummaryUsesExtractedSourceEvidence
multiSourceReportFailsWhenOneSourceHasNoDistinctiveFacts
corruptDocxCannotBeSummarizedWithoutGuessing
```

## Fix Direction

Implementation order:

1. Extend source-to-target artifact parsing to collect multiple source files.
2. Make source-derived verification document-aware through `DocumentExtractionService` or the same capability parser path as `read_file`.
3. Change source-derived verification from aggregate overlap to per-source coverage. This is implemented for readable text sources by the T307 slice; it still needs document-aware extraction coverage for this ticket.
4. Add private-mode artifact scan tests for document-source reports.
