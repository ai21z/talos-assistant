# T320 - PDF/Office Extraction And Generation Claims Must Stay Separate

Status: done - README now explicitly separates extraction support from binary document generation
Severity: high
Release gate: yes for document capability claims
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19 / 2026-05-20
Owner: unassigned

## Problem

The transcript included a user concern that there is "no pdf creation, or read." The actual transcript showed an unsupported PDF creation request and a Markdown file named `pdf_guide.md`; it did not test reading a real `.pdf`.

Talos must keep these claims separate:

- reading/extracting text-bearing PDF/DOCX/XLS/XLSX files
- creating valid binary PDF/DOCX/XLS/XLSX files
- converting source formats to binary document outputs

## Expected Behavior

- Refuse to create valid PDF/DOCX/XLS/XLSX files unless a real supported document-generation path exists.
- Read/extract supported text-bearing documents only through the documented extraction path.
- Report OCR/scanned/corrupt/encrypted limitations honestly.
- Never use a Markdown file named like `pdf_guide.md` as evidence that PDF extraction works.

## Proposed Audit Probes

- read a valid text PDF fixture
- read a scanned/no-text PDF fixture and report OCR limitation
- attempt to create a PDF and refuse binary generation
- create a supported Markdown/HTML source artifact as an alternative
- verify prompt-debug/artifact scans for private-document canaries when private mode is active

## Related Tickets

- T291 local PDF text extraction
- T292 local Word DOCX extraction
- T293 local Excel extraction
- T295 private document provenance boundary
- T305 private document provenance ToolResult boundary

## Closure Evidence

Implemented on 2026-05-20:

- README capability matrix states:
  - PDF: text extraction for text-bearing PDFs, not PDF creation, scanned-PDF OCR, visual layout review, or guaranteed reading order.
  - Word: text extraction for `.docx`, not `.doc`, embedded-object/layout fidelity, or valid Word document generation.
  - Excel: visible-cell extraction for `.xls`/`.xlsx`, not formula recalculation, macro execution, hidden-sheet guarantees, chart interpretation, or valid workbook generation.
  - Image/OCR and PowerPoint remain frozen out of beta product claims.
- README explicitly states that Talos cannot create valid PDF/DOCX/XLS/XLSX files with the current local text-file tool surface.
- Regression coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.docs.ReadmePrivacyCopyTest" --no-daemon
```
