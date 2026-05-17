# T305 - Private Document Provenance ToolResult Boundary

Status: partial implementation, open
Severity: P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-17
Owner: unassigned

## Problem

Talos can extract PDF/DOCX/XLS/XLSX text, and `DocumentExtractionResult` carries extraction provenance and `modelHandoffAllowed`. Before this ticket, that privacy decision was lost when `ReadFileTool` converted the extraction result into a plain `ToolResult.output`. The model-loop boundary then treated extracted private document text as ordinary successful tool output unless the file path also matched protected-path rules.

That is not enough for private-document beta. Private document text often contains names, addresses, medical facts, invoices, lease terms, salaries, or tax facts that do not look like `.env` secrets or canary strings.

## Evidence from current code

- `ToolResult` now carries `ToolContentMetadata`: `src/main/java/dev/talos/tools/ToolResult.java:10`, `src/main/java/dev/talos/tools/ToolContentMetadata.java:11`.
- Private extracted document text has a distinct privacy class when model handoff is not allowed: `src/main/java/dev/talos/tools/ToolContentMetadata.java:24`, `src/main/java/dev/talos/tools/ToolContentMetadata.java:66`.
- `PrivateDocumentPolicy` now owns private-mode document handoff and RAG-indexing decisions: `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:22`, `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:54`, `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:85`.
- `DocumentExtractionService` now asks `PrivateDocumentPolicy` for model handoff: `src/main/java/dev/talos/core/extract/DocumentExtractionService.java:75`, `src/main/java/dev/talos/core/extract/DocumentExtractionService.java:236`.
- `ReadFileTool` preserves extraction metadata when creating `ToolResult`: `src/main/java/dev/talos/tools/impl/ReadFileTool.java:139`, `src/main/java/dev/talos/tools/impl/ReadFileTool.java:145`.
- `ToolCallExecutionStage` now withholds successful tool results whose metadata says model handoff is not allowed: `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java:283`, `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java:570`.
- `Indexer` now enforces `PrivateDocumentPolicy.ragIndexAllowed(...)` before returning extracted text for indexing.
- `Indexer` metadata now hashes privacy config, so disabling private-document RAG indexing makes prior indexes stale.
- `/privacy status` exposes private document extraction model-context, artifact, and RAG-index opt-ins.

## Evidence from tests/audits

- `private_mode_document_extraction_is_not_model_handoff_by_default`: `src/test/java/dev/talos/core/extract/DocumentExtractionServiceTest.java:79`.
- `private_mode_docx_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java:138`.
- `private_mode_xlsx_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java:177`.
- `privateModeDocxSendToModelStillCarriesPrivateDocumentMetadata`: `src/test/java/dev/talos/tools/impl/ReadFileToolTest.java:227`.
- `rag_index_command_refuses_private_mode_when_rag_disabled`: `src/test/java/dev/talos/cli/launcher/RagIndexCmdPrivateModeTest.java:20`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_pdfNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_docxNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_xlsxNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateDocumentRagIndexingPolicyChangeMarksOldIndexDirtyAndRebuildsWithoutPrivateChunks`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `artifact_scan_detects_private_document_fact_canary_and_redacts_snippet`: `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java`.

Focused command passed on 2026-05-17:

```text
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.cli.launcher.RagIndexCmdPrivateModeTest" --no-daemon
```

Additional verification passed on 2026-05-17:

```text
./gradlew.bat test --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
```

## User impact

In private mode, a user can ask Talos to read a DOCX/XLSX-style private document without that private extracted text being handed back into the model loop by default. The model sees a truthful withheld-content placeholder instead of raw private facts.

## Product risk

P0 for private-document beta. Without this boundary, private-document extraction creates a false privacy claim: the extractor looks policy-aware, but the model pipeline can still receive raw extracted facts.

## Runtime boundary affected

Document extraction, read-file tool output, tool-result model-message handoff, RAG indexing, prompt-debug/provider-body captures, sessions, turn logs, traces, live-audit artifacts, and final answer synthesis.

## Non-goals

- No claim that prompt-debug/session/trace redaction is fully provenance-aware yet.
- No claim that private-document beta is ready.
- No image/OCR or PowerPoint beta support.
- No broad architecture cleanup in this ticket.

## Required behavior

- `ToolResult` must preserve content provenance/handoff metadata.
- In private mode, extracted PDF/DOCX/XLS/XLSX text defaults to local-display-only.
- Model-loop messages receive a truthful withheld-content placeholder when `modelHandoffAllowed=false`.
- Top-level `rag-index` must not bypass the same private-mode RAG guard used by `/reindex`.
- Raw private facts must not rely on secret/canary regexes for model-context protection.
- Private-document RAG indexing must be blocked at the `Indexer` boundary, not only at command launchers.
- Index metadata must become stale when privacy config affecting index content changes.
- `/privacy status` must surface document extraction opt-ins explicitly.

## Proposed implementation

Keep the first implementation small:

- Add `ToolContentMetadata` to `ToolResult`.
- Add `PrivateDocumentPolicy`.
- Have `DocumentExtractionService` compute `modelHandoffAllowed` from the policy.
- Have `ReadFileTool` attach extraction metadata to successful document-extraction results.
- Have `ToolCallExecutionStage` replace non-handoff tool outputs before appending tool results to model messages.
- Route top-level `RagIndexCmd` through `RagService.reindex(...)`.
- Enforce `PrivateDocumentPolicy.ragIndexAllowed(...)` in `Indexer.parseIndexableText(...)`.
- Include privacy config in index policy metadata.
- Add deterministic private-document fact canaries to the artifact scanner.

## Tests

Implemented:

- `private_mode_document_extraction_is_not_model_handoff_by_default`
- `private_mode_docx_extraction_is_withheld_from_model_context`
- `private_mode_xlsx_extraction_is_withheld_from_model_context`
- `privateModeDocxSendToModelStillCarriesPrivateDocumentMetadata`
- `rag_index_command_refuses_private_mode_when_rag_disabled`
- `privateMode_ragEnabled_privateDocRagIndexingFalse_pdfNotIndexed`
- `privateMode_ragEnabled_privateDocRagIndexingFalse_docxNotIndexed`
- `privateMode_ragEnabled_privateDocRagIndexingFalse_xlsxNotIndexed`
- `privateDocumentRagIndexingPolicyChangeMarksOldIndexDirtyAndRebuildsWithoutPrivateChunks`
- `private_document_extraction_privacy_defaults_are_explicit_and_safe`
- `artifact_scan_detects_private_document_fact_canary_and_redacts_snippet`

Still required:

- `private_mode_pdf_extraction_is_withheld_from_model_context`
- `private_mode_xls_extraction_is_withheld_from_model_context`
- `prompt_debug_redacts_private_document_fact_canary`
- `provider_body_does_not_contain_private_document_fact_canary`
- `session_turn_log_redacts_private_document_fact_canary`
- `local_trace_redacts_private_document_fact_canary`
- `artifact_scan_distinguishes_private_fact_canary_from_secret_canary`
- `private_mode_explicit_send_to_model_for_extracted_document_requires_config_and_is_traced`

## Acceptance criteria

- No private-mode extracted document text enters model context by default.
- RAG indexing in private mode refuses by default through both slash and top-level commands.
- All persisted runtime artifacts are either provenance-aware redacted or verified to never receive raw private extracted text.
- Private-document live audit includes ordinary private facts, not only token-shaped canaries.
- Full `clean check e2eTest` passes after the artifact tests are added.

## Rollback / migration notes

If provenance-aware artifact redaction cannot be completed, private-document support must remain local-display-only and not release-ready. If that is still unsafe, disable document extraction in private mode until the full artifact boundary is proven.

## Open questions

- Should private-mode extracted document handoff use only config opt-in, or also a per-turn approval scope distinct from protected-path reads?
- Should `ToolResult.output` be split into local-display output and model-visible output to make accidental handoff impossible?
- Should private-document provenance also cover generated assistant answers derived from document text?

## Related files

- `src/main/java/dev/talos/tools/ToolResult.java`
- `src/main/java/dev/talos/tools/ToolContentMetadata.java`
- `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java`
- `src/main/java/dev/talos/core/extract/DocumentExtractionService.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/cli/launcher/RagIndexCmd.java`
