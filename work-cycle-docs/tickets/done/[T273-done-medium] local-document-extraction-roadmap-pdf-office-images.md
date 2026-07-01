# T273 - Local Document Extraction Roadmap for PDF, Office, and Images

Status: done - superseded by detailed extraction/document tickets T294 and T299-T304
Severity: high
Release gate: yes for document-capability claims; images and PowerPoint are v1/open issues, not beta gates
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos now has beta-core extraction for text-bearing PDFs, DOCX, and XLS/XLSX. Images/scans/OCR and PowerPoint are frozen out of beta and remain open v1 issues. Archives and arbitrary binaries remain unsupported.

## Evidence from current code

`FileCapabilityPolicy` distinguishes extractable text PDFs/DOCX/XLS/XLSX from deferred images/OCR, PowerPoint, archives, compiled artifacts, and binaries. `ReadFileTool`, `GrepTool`, slash grep, and `Indexer` route extractable document text through the central extraction service.

## Evidence from external/source crosscheck

Apache Tika, Apache POI, PDFBox, and Tesseract show local extraction/OCR is feasible, but agent tool output becomes model context. Any extraction feature must provide deterministic local evidence, confidence/partial metadata, privacy redaction, and artifact scanning before model summarization.

## User impact

Users can ask Talos to extract text from supported beta-core documents, but cannot safely ask it to summarize arbitrary private paperwork yet. Images, scans, and PowerPoint should be converted to text/Markdown/CSV or handled outside beta.

## Product risk

Product copy that implies full-fidelity PDF/Word/Excel review, image understanding/OCR beta support, or PowerPoint support would be false.

## Runtime boundary affected

Future document extraction, OCR, parser trust, metadata/content distinction, local-only processing, model-context handoff, logs, traces, sessions, and RAG indexes.

## Non-goals

- Remote/cloud extraction by default.
- Image/OCR beta support.
- PowerPoint support in beta.
- Office/PDF editing.

## Required behavior

Future extraction must be local-first, explicit, auditable, sanitized before model/artifact use, and clear about extraction confidence and partial reads.

## Proposed implementation

Use this ticket as the parent roadmap. Detailed tickets now split the work:

- T290 document extraction architecture spine
- T291 local PDF text extraction
- T292 local Word DOCX extraction
- T293 local Excel XLSX extraction
- T294 local image OCR extraction, frozen for v1
- T295 extraction privacy/artifact boundary
- T296 extraction RAG index integration
- T299 valid fixtures, BDD, and live audit
- T300 dependencies, performance, and resource limits
- T301 capability docs and release claims
- T302 PowerPoint deferred to full release
- T303 file capability policy V3 extraction state machine
- T304 extraction cache and invalidation

## Tests

- parser-specific extraction tests from T291-T293 for beta-core formats; T294 remains v1/open
- privacy/artifact tests from T295
- RAG/index tests from T296
- BDD/live audit tests from T299
- performance/limit tests from T300

## Acceptance criteria

- Detailed architecture tickets exist and prevent overclaiming before implementation.
- This ticket is closed only when the child tickets are either done or explicitly descoped from beta.

## Rollback / migration notes

None.

## Open questions

- What OCR provider/install path should Talos support for v1?

## Related files

- `src/main/java/dev/talos/core/ingest/*`
- `work-cycle-docs/reports/document-extraction-architecture-strategy.md`
- T290-T304
