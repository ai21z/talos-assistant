# T305 - Private Document Provenance ToolResult Boundary

Status: done - ToolResult provenance and model-handoff boundary implemented
Severity: P0 for private-document beta
Release gate: no for ToolResult boundary; remaining private-document release gate is T295
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
- `runtime_sanitizer_redacts_private_document_fact_canaries`: `src/test/java/dev/talos/runtime/policy/SensitiveLogRedactionTest.java`.
- `prompt_debug_markdown_redacts_private_document_fact_canaries`: `src/test/java/dev/talos/cli/prompt/PromptDebugInspectorPrivateDocumentTest.java`.
- `provider_body_json_redacts_private_document_fact_canaries`: `src/test/java/dev/talos/cli/prompt/PromptDebugInspectorPrivateDocumentTest.java`.
- `privateDocumentFactCanariesAreRedactedBeforeHistoryPersistence`: `src/test/java/dev/talos/runtime/MemoryUpdateListenerTest.java`.
- `savedSessionRedactsPrivateDocumentFactCanaries`: `src/test/java/dev/talos/runtime/JsonSessionStoreTest.java`.
- `turnJsonlRedactsPrivateDocumentFactCanaries`: `src/test/java/dev/talos/runtime/JsonSessionStoreTest.java`.
- `localTraceJsonRedactsPrivateDocumentFactCanaries`: `src/test/java/dev/talos/runtime/JsonSessionStoreTest.java`.
- `writesStructuredRecordWithPrivateDocumentFactCanariesRedacted`: `src/test/java/dev/talos/runtime/JsonTurnLogAppenderTest.java`.
- `redactsPrivateDocumentFactCanaries`: `src/test/java/dev/talos/runtime/trace/TraceRedactorTest.java`.

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

Additional artifact-sink suite passed on 2026-05-17 after a failing red run:

```text
./gradlew.bat test --tests "*PromptDebugInspectorPrivateDocumentTest" --tests "*SensitiveLogRedactionTest" --tests "*MemoryUpdateListenerTest" --tests "*JsonSessionStoreTest" --tests "*JsonTurnLogAppenderTest" --tests "*TraceRedactorTest" --no-daemon
./gradlew.bat test --tests "*ArtifactCanary*" --tests "*PromptDebug*" --tests "*JsonSessionStore*" --tests "*JsonTurnLogAppender*" --tests "*MemoryUpdateListener*" --tests "*TraceRedactor*" --tests "*SensitiveLog*" --tests "*ProtectedReadScope*" --tests "*IndexerPrivateDocumentPolicy*" --tests "*ConfigPrivacyDefaults*" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
```

Focused two-model live audit passed on 2026-05-18:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -StopStaleServers
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260518-001437,local/manual-workspaces/capability-live-audit-20260518-001437" "-PartifactScanAllowlist=<source fixture allowlist>" --no-daemon
```

Audit evidence:

- Audit ID: `capability-live-audit-20260518-001437`.
- GPT-OSS and Qwen each ran 16 beta-core prompts.
- Private-mode PDF/DOCX/XLSX prompts used ordinary private-document fact fixtures.
- Both models read the private document targets and received/returned withheld-content behavior rather than raw extracted facts.
- Generated runtime artifacts did not contain the raw private-document fact fixture values in direct grep or targeted artifact scan.

Private-folder bank live audit passed on 2026-05-18:

```text
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank -StopStaleServers
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260518-004603,local/manual-workspaces/capability-live-audit-20260518-004603" "-PartifactScanAllowlist=<source fixture allowlist>" --no-daemon
```

Audit evidence:

- Audit ID: `capability-live-audit-20260518-004603`.
- GPT-OSS and Qwen each ran 22 prompts.
- Private-folder probes covered `/show`, private-mode reindex refusal, private-mode retrieve-style behavior, and protected-read denial.
- The run generated a manual runbook for approval-sensitive probes.
- Targeted artifact scan passed.

Bug found and fixed:

- Private-mode `/show` could use an existing Lucene snippet after a developer-mode reindex, bypassing the local-display extraction path.
- `ShowCommand` now skips index lookup in private mode unless private-mode RAG is explicitly enabled.
- Regression coverage: `private_mode_show_skips_index_snippet_when_private_rag_disabled`.

## User impact

In private mode, a user can ask Talos to read a DOCX/XLSX-style private document without that private extracted text being handed back into the model loop by default. The model sees a truthful withheld-content placeholder instead of raw private facts.

## Product risk

P0 for private-document beta. Without this boundary, private-document extraction creates a false privacy claim: the extractor looks policy-aware, but the model pipeline can still receive raw extracted facts.

## Runtime boundary affected

Document extraction, read-file tool output, tool-result model-message handoff, RAG indexing, prompt-debug/provider-body captures, sessions, turn logs, traces, live-audit artifacts, and final answer synthesis.

## Non-goals

- No claim that prompt-debug/session/trace redaction is fully provenance-aware yet.
- No claim that deterministic private-document fact canary redaction is general PII detection.
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
- Move deterministic private-document fact canary redaction into `ProtectedContentPolicy` so prompt-debug/session/trace/log helpers share the same runtime sanitizer.

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
- `prompt_debug_markdown_redacts_private_document_fact_canary`
- `provider_body_does_not_contain_private_document_fact_canary`
- `session_turn_log_redacts_private_document_fact_canary`
- `local_trace_redacts_private_document_fact_canary`
- `private_mode_pdf_extraction_is_withheld_from_model_context`
- `private_mode_xls_extraction_is_withheld_from_model_context`
- `private_mode_withheld_document_final_answer_redacts_model_fabricated_private_fact`
- `private_mode_document_send_to_model_opt_in_allows_model_handoff`
- `file_fallback_rejects_workspace_escape`
- `private_mode_show_skips_index_snippet_when_private_rag_disabled`
- `document_fallback_extracts_docx_for_local_display_in_private_mode`
- `document_fallback_extracts_pdf_for_local_display_in_private_mode`
- `document_fallback_extracts_xls_for_local_display_in_private_mode`
- `document_fallback_extracts_xlsx_for_local_display_in_private_mode`

Residual work moved to T295:

- per-turn extracted-document send-to-model approval UX, separate from config-only opt-in
- explicit send-to-model tracing for extracted documents
- broader private-folder live audit over larger/adversarial private-document fixtures
- synchronized or human-operated approval transcript coverage

## Acceptance criteria

- No private-mode extracted document text enters model context by default.
- RAG indexing in private mode refuses by default through both slash and top-level commands.
- All persisted runtime artifacts are either provenance-aware redacted or verified to never receive raw private extracted text.
- Scripted final answers do not preserve configured private-document fact canaries after runtime withheld the document result from model context.
- Private-document live audit includes ordinary private facts, not only token-shaped canaries.
- Full `clean check e2eTest` passes after the artifact tests are added.

Current status against acceptance criteria:

- Focused beta-core and private-folder bank live audits now include ordinary private facts and passed for generated PDF/DOCX/XLSX fixtures.
- The ToolResult provenance boundary is closed. Private-document beta remains blocked by T295 because per-turn send-to-model approval UX/tracing and broad real-world private-document fixture evidence are not complete.

## 2026-05-20 resolution

Focused evidence passed again:

```text
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorPrivateDocumentTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.JsonTurnLogAppenderTest" --tests "dev.talos.runtime.trace.TraceRedactorTest" --tests "dev.talos.api.TalosKnowledgeEnginePrivacyTest" --no-daemon
```

This ticket is done because `ToolResult` now carries `ToolContentMetadata`, `ReadFileTool` attaches extracted-document metadata, the model-loop boundary withholds non-handoffable content, and indexing/artifact sinks have focused coverage. The remaining release blocker is not "metadata lost at ToolResult"; it is the broader private-document release gate in T295.

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
