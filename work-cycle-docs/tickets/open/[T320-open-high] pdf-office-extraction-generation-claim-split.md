# T320 - PDF/Office Extraction And Generation Claims Must Stay Separate

Status: still-open - document extraction versus binary generation claim split remains release-copy work
Severity: high
Release gate: yes for document capability claims
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
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
