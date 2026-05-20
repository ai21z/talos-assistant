# T292 - Local Word DOCX Extraction

Status: done - DOCX text extraction implemented for beta scope
Severity: P0 for beta
Release gate: no for DOCX text extraction; legacy DOC and broader private-document release claims remain gated separately
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Talos now supports DOCX text extraction for beta. The original gap was that Word document reads were refused or could be misrepresented. Legacy `.doc`, full layout fidelity, comments/tracked changes/embedded objects, and Word editing remain out of scope.

## Evidence from current code

- `.docx` is classified as extractable when document extraction is enabled.
- Legacy `.doc` remains deferred/unsupported and must be reported honestly.
- DOCX extraction is routed through `DocumentExtractionService`, not ad hoc tool code.
- `ReadFileTool`, grep/slash grep, RAG indexing, and local display paths share the extraction boundary.
- Final-answer truthfulness coverage prevents unsupported/deferred format overclaims.

## Evidence from source crosscheck

Apache POI documents Word and Office text extraction. Apache POI also recommends Apache Tika for turn-key text and metadata extraction when broader document handling is desired.

## User impact

Users with house paperwork, administrative documents, contracts, letters, or project documents cannot rely on Talos to inspect DOCX content yet.

## Product risk

High. Word files commonly contain private personal, legal, and business content. Extraction must not bypass protected-read scope or artifact redaction.

## Runtime boundary affected

DOCX read, DOCX search, DOCX indexing, model context, prompt-debug, provider-body, trace/session persistence, and final answer.

## Non-goals

- No Word editing or valid DOCX generation.
- No full fidelity layout extraction.
- No remote conversion through LibreOffice or cloud services by default.
- Legacy `.doc` may remain unsupported if DOCX is the beta scope, but docs must state that clearly.

## Required behavior

- Extract plain text from valid DOCX locally.

## 2026-05-20 resolution

Closed for beta DOCX text extraction. Broader Word semantics and private-paperwork release claims remain out of scope for this ticket.

Focused evidence:

```text
.\gradlew.bat test --tests "dev.talos.cli.modes.UnsupportedFinalAnswerTruthfulnessTest" --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --tests "dev.talos.core.extract.DocumentExtractionCanonicalFixturesTest" --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```
- Preserve paragraph/table/list order well enough for user-facing summaries.
- Report unsupported legacy DOC separately if not implemented.
- Report corrupt/password-protected documents honestly.
- Redact protected markers and secret-like values.
- Track extraction metadata and partial warnings.

## Proposed implementation

Implement a DOCX adapter behind T290's extraction interface. Use Apache POI directly for beta so Talos controls exactly which DOCX structures are extracted. Do not let `ReadFileTool` know parser details; it should call the central service.

DOCX extraction should be content-oriented, not layout-perfect. Headers, footers, tables, comments, tracked changes, and embedded objects must either be extracted intentionally or listed as unsupported/partial warnings.

Beta scope decision: implement `.docx` first. Do not market this as generic "Word document" support unless legacy `.doc` is either implemented and tested or explicitly excluded in every capability matrix. If the product copy says "Word" without a `.docx` qualifier, that is an overclaim.

## Tests

- `docx_text_extraction_reads_known_paragraphs`
- `docx_table_text_is_included_with_sheet_like_boundaries`
- `docx_headers_footers_comments_policy_is_reported`
- `docx_tracked_changes_policy_is_reported`
- `docx_extraction_redacts_secret_like_text`
- `protected_docx_private_mode_does_not_enter_model_context`
- `docx_artifacts_do_not_contain_raw_canary`
- `corrupt_docx_reports_failed_extraction`
- `legacy_doc_reports_unsupported_or_extracts_with_explicit_adapter`
- `docx_rag_indexing_uses_sanitized_extracted_text_only`

## Acceptance criteria

- Valid DOCX text can be read, searched, cited, and indexed when allowed.
- Failed/partial DOCX extraction is explicit.
- DOCX answers state partial limitations when headers, comments, tracked changes, or embedded objects are skipped.
- No DOCX raw protected content appears in artifacts unless explicitly allowed by unsafe maintainer config.

## Rollback / migration notes

Keep `.docx` classified unsupported until the adapter passes all required tests. Legacy `.doc` can remain unsupported if separated in docs and capability matrix.

## Open questions

- Should legacy `.doc` be implemented before public beta, or should beta copy say "DOCX only"?

## Related files

- `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/core/index/Indexer.java`

## 2026-05-16 Implementation update

Status: implemented for DOCX text extraction; legacy `.doc` remains deferred.

Code evidence:

- `DocumentExtractionService` extracts DOCX through POI XWPF.
- `gradle.properties` pins `poiVersion=5.5.1`.
- `FileCapabilityPolicy` separates `.docx` from deferred legacy `.doc`.
- `ReadFileTool`, grep, slash grep, and RAG indexing route through extraction-aware policy.

Verification:

- `DocumentExtractionAdaptersTest` passed.
- Full `./gradlew.bat clean check e2eTest --no-daemon` passed.
- Two-model beta-core live audit `capability-live-audit-20260516-210854` passed `06-docx-summary`.

Remaining blockers:

- Headers, footers, comments, tracked changes, embedded objects, corrupt/password-protected files, and legacy `.doc` need explicit fixtures and policy.
- Do not claim generic "Word document review"; claim DOCX text extraction only.


