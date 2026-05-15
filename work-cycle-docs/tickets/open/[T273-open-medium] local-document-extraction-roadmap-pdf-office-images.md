# T273 - Local Document Extraction Roadmap for PDF, Office, and Images

Status: open
Severity: medium
Release gate: no for developer/text beta; yes for document-reader positioning
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Talos cannot yet reliably extract PDF, Word, Excel, PowerPoint, image, scan, archive, or arbitrary binary contents with the current local text-tool surface.

## Evidence from current code

`UnsupportedDocumentFormats` explicitly blocks PDF/Office direct reads/writes but does not provide parsers. Images, scans, archives, compiled artifacts, and binaries are not centrally classified.

## Evidence from external/source crosscheck

Agent tool output becomes model context. Any extraction feature must provide deterministic local evidence and confidence metadata before model summarization.

## User impact

Users cannot safely ask Talos to summarize arbitrary private paperwork yet. They should convert documents to text/Markdown/CSV first.

## Product risk

Product copy that implies PDF/Office/image support would be false.

## Runtime boundary affected

Future document extraction, OCR, parser trust, metadata/content distinction, local-only processing.

## Non-goals

- Implement extraction in the T267 hotfix unless scoped separately.
- Remote/cloud extraction by default.

## Required behavior

Future extraction must be local-first, explicit, auditable, and clear about extraction confidence and partial reads.

## Proposed implementation

Create a roadmap for safe local parsers, OCR policy, archive listing/extraction policy, and confidence/status metadata.

## Tests

- parser-specific extraction tests
- OCR confidence tests
- partial extraction final-answer tests
- local-only/no-network tests

## Acceptance criteria

- Roadmap exists and prevents overclaiming before implementation.

## Rollback / migration notes

None.

## Open questions

- Which formats should ship first: PDF text extraction, DOCX text, XLSX-to-CSV, or OCR images?

## Related files

- `src/main/java/dev/talos/core/ingest/*`
- future document extraction docs

