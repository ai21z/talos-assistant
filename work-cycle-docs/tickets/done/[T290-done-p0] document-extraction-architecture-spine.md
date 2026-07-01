# T290 - Document Extraction Architecture Spine

Status: done - beta extraction spine implemented
Severity: P0 for beta
Release gate: no for beta extraction architecture spine; residual hardening remains tracked by T299/T300/T303/T304/T295
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos cannot become a trustworthy local document assistant by adding PDF, Word, Excel, and image parsing directly into individual tools. That would scatter security, redaction, metadata, partial-read handling, and performance limits across `ReadFileTool`, `GrepTool`, `Indexer`, `RetrieveTool`, and final-answer shaping.

Re-review correction: a central service is necessary but not sufficient. It must expose a strict contract that prevents raw parser output from becoming a generic string passed around the runtime.

## Evidence from current code

- `DocumentExtractionService`, `DocumentExtractionRequest`, `DocumentExtractionResult`, `DocumentExtractionStatus`, warnings, provenance, and format adapters now provide the central extraction boundary.
- `ReadFileTool`, `GrepTool`, slash grep, `Indexer`, and `/show` route supported document extraction through the shared service rather than adding parser-specific logic directly in each caller.
- `ToolResult` and `ToolContentMetadata` preserve privacy/handoff metadata after extraction.
- `PrivateDocumentPolicy` owns private-mode document handoff and RAG-indexing decisions.
- Central privacy/redaction remains in `ProtectedContentPolicy` and artifact-specific redactors.

## Evidence from source crosscheck

- Apache Tika documents broad metadata/text extraction across document families and explicitly lists PDF, Microsoft Office, OpenDocument, archives, and text formats.
- Apache POI documents Office text extractors and recommends Apache Tika for turn-key extraction.
- PDFBox provides PDF text extraction tooling.
- Tesseract provides local command-line OCR for images.
- Gemini CLI and OpenAI Codex both support the design principle that tool execution and approval policy belong in the harness, not in model prose.

## User impact

Without a central extraction spine, users may get inconsistent behavior: a file might be rejected by direct read, indexed by RAG, summarized from stale extracted text, or logged differently depending on which path touched it.

## Product risk

High. Fragmented extraction creates privacy leaks, false document summaries, inconsistent audit evidence, and hard-to-test behavior.

## Runtime boundary affected

Read, grep/search, RAG indexing, retrieval snippets, model context, prompt-debug, provider-body captures, traces, session logs, final-answer truthfulness, and live audit artifacts.

## Non-goals

- No remote/cloud document extraction.
- No PDF/Office/image editing in this ticket.
- No archive unpacking beyond explicit future policy.
- No PowerPoint beta requirement; PPT can remain unsupported until full release.

## Required behavior

Create one central extraction service that all document-reading paths use. The service must return structured results with:

- source path
- detected format family
- extraction status
- safe extracted text for public consumers
- extraction warnings
- page/sheet/image metadata where applicable
- partial/failed/encrypted/password-protected indicators
- byte/character/page/sheet limits
- privacy redaction metadata
- stable provenance for citations and audits
- adapter name and adapter version
- extraction policy version
- "model handoff allowed" flag derived from privacy mode and protected-read scope

Raw parser text must not be a public field on a Jackson-serializable result. If raw text exists at all, it should live in a short-lived internal/package-private type and be discarded after safe text and redaction metadata are produced.

## Proposed implementation

Add a new `dev.talos.core.extract` package with:

- `DocumentExtractionService`
- `DocumentExtractionRequest`
- `DocumentExtractionResult`
- `DocumentExtractionStatus`
- `DocumentFormatFamily`
- `DocumentExtractionWarning`
- `DocumentExtractionLimits`
- `DocumentExtractionProvenance`
- format-specific adapters behind a small interface such as `DocumentExtractor`

Initial adapters:

- PDF text extraction adapter.
- DOCX text extraction adapter.
- XLSX workbook-to-structured-text adapter.
- Image OCR adapter with explicit dependency detection.
- Unsupported/deferred adapter for PPT/PPTX and archives.

All callers should receive `DocumentExtractionResult`, never raw parser output.

Recommended beta dependency stance:

- Prefer narrow direct adapters first: PDFBox for PDF, Apache POI for DOCX/XLSX, and a bounded local Tesseract command adapter for OCR.
- Do not start with Apache Tika as the main parser layer. Tika is broad and can traverse many content families, including archives and optional OCR. That breadth makes policy control harder for beta. Tika can be revisited after Talos proves strict format states, archive denial, and artifact scanning.

Architectural constraints:

- Static file capability and dynamic extraction outcome must be separate concepts. A `.pdf` may be `EXTRACTABLE_TEXT_ENABLED`, but a specific PDF can still be `ENCRYPTED`, `OCR_REQUIRED`, `CORRUPT`, `PARTIAL`, or `LIMIT_EXCEEDED`.
- `DocumentExtractionResult` should expose sanitized text as the default text field. Raw parser text must not be stored in generic `Map<String, Object>`, trace/session DTOs, Jackson-serializable records, or cache rows.
- The service must take a `DocumentExtractionRequest` that includes caller intent: `READ`, `SEARCH`, `INDEX`, `COMPARE`, or `LOCAL_DISPLAY`. Different intents have different privacy and truncation rules.
- Every result must include adapter identity, adapter version, policy version, source file hash, and limit decisions so RAG and cache invalidation can prove what produced a chunk.
- The first implementation pass should add the service and unsupported/deferred adapters without enabling PDF/DOCX/XLSX/OCR. That gives tests a stable spine before parser dependencies are added.

Caller contracts:

- `ReadFileTool` formats safe extracted text with provenance and truncation notes.
- `GrepTool` searches safe extracted text only when extraction/search policy allows it and reports skipped/partial documents.
- `Indexer` indexes safe extracted text only through extraction-aware policy and metadata.
- `RetrieveTool` remains downstream of sanitized indexed chunks and still applies retrieval-time sanitization.
- Final-answer shaping must treat extraction statuses as evidence: `FAILED`, `PARTIAL`, `OCR_REQUIRED`, `ENCRYPTED`, and `LIMIT_EXCEEDED` cannot become "reviewed successfully."

## Tests

- `DocumentExtractionServiceTest`
- `DocumentExtractionResultTest`
- `DocumentExtractionStatusTest`
- `DocumentExtractionLimitsTest`
- `DocumentExtractionSerializationTest`
- parser adapter unit tests with valid fixtures
- privacy redaction tests
- resource-limit tests
- corrupt/encrypted/unsupported fixture tests
- no-network extraction test

## Acceptance criteria

- `ReadFileTool`, grep/search, and `Indexer` do not call PDF/Office/OCR libraries directly.
- All extraction output passes through `ProtectedContentPolicy` before model context or artifacts.
- Partial/failed extraction is explicit and testable.
- Public extraction result serialization cannot include raw extracted text.
- Every adapter emits provenance and status metadata.
- Existing unsupported-format truthfulness remains intact until a format adapter is implemented and tested.

## Rollback / migration notes

Keep existing unsupported-format blocks as the fallback path. Enable each extractor family behind tests and config so a broken adapter can be disabled without removing the architecture.

## Open questions

- Should image OCR require explicit config because it may be slow and installation-dependent?
- Should `ReadFileTool` gain a separate `extract_document` mode, or should extraction happen automatically for extractable formats once enabled?

## Related files

- `src/main/java/dev/talos/core/ingest/ParserUtil.java`
- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java`
- `src/main/java/dev/talos/core/context/ContextPacker.java`

## 2026-05-20 resolution

Closed for the beta architecture spine. Remaining work is not "create the spine"; it is deeper fixture coverage, extraction state-machine hardening, resource/caching policy, and private-document release evidence.

Focused evidence:

```text
.\gradlew.bat test --tests "dev.talos.cli.modes.UnsupportedFinalAnswerTruthfulnessTest" --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```
