# Private Document Provenance Boundary Audit

Date: 2026-05-17
Branch: `v0.9.0-beta-dev`

## 1. Executive verdict

Private-document beta is still not release-ready.

This pass closes the first concrete model-context leak in the new document extraction path: private-mode extracted DOCX/XLSX-style document text is no longer treated as ordinary tool output when the tool result is appended back into the model loop.

Follow-up work in the same gate also closes the remaining RAG indexing policy hole found after review: `Indexer` now honors `PrivateDocumentPolicy.ragIndexAllowed(...)`, records privacy skips explicitly, and treats privacy-config changes as index-metadata changes.

This pass adds deterministic artifact-sink proof for ordinary private-document fact canaries. `ProtectedContentPolicy.sanitizeText(...)` now redacts the configured private-document fact canary class centrally, so prompt-debug markdown, provider-body JSON formatting, session snapshots, turn JSONL, local trace JSON, conversation memory, and log/trace sanitizers no longer depend only on token-shaped secret regexes in the covered tests.

The fix remains partial because this is deterministic canary proof, not general PII detection, and the two-model live private-document audit has not been rerun after this pass.

## 2. Claim challenged

The claim under review was:

> Talos has document extraction provenance fields, but the runtime does not actually use them as a privacy control boundary.

Verdict: correct before this pass. The dangerous part was not the extractor itself; it was the conversion from `DocumentExtractionResult` to plain `ToolResult.output`, followed by model-loop formatting as ordinary successful tool output.

## 3. Code state before this pass

- `DocumentExtractionResult` carried `modelHandoffAllowed`, but `ReadFileTool` formatted extracted text directly into `ToolResult.output`.
- `ToolResult` did not carry content provenance/handoff metadata.
- `ToolCallExecutionStage` withheld approved protected-path reads, but did not withhold ordinary extracted private document text.
- Top-level `rag-index` constructed `Indexer` directly instead of using `RagService.reindex(...)`, bypassing the private-mode indexing guard.

## 4. Implemented boundary

### Tool result provenance

- Added `ToolContentMetadata`: `src/main/java/dev/talos/tools/ToolContentMetadata.java:11`.
- Extended `ToolResult` with `contentMetadata`: `src/main/java/dev/talos/tools/ToolResult.java:10`, `src/main/java/dev/talos/tools/ToolResult.java:15`.
- Backward-compatible constructors/factories preserve existing tool behavior while allowing document extraction tools to attach metadata.

### Private document policy

- Added `PrivateDocumentPolicy`: `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:13`.
- Private mode treats extracted document text as local-display-only by default: `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:30`.
- Private-mode RAG indexing of extracted document text requires both private-mode RAG and an explicit document-extraction RAG opt-in: `src/main/java/dev/talos/runtime/policy/PrivateDocumentPolicy.java:61`.

### Extraction handoff

- `DocumentExtractionService` now asks `PrivateDocumentPolicy` for model-handoff decisions: `src/main/java/dev/talos/core/extract/DocumentExtractionService.java:75`, `src/main/java/dev/talos/core/extract/DocumentExtractionService.java:236`.
- `ReadFileTool` attaches extraction metadata to successful extraction tool results: `src/main/java/dev/talos/tools/impl/ReadFileTool.java:139`, `src/main/java/dev/talos/tools/impl/ReadFileTool.java:145`.
- `ToolCallExecutionStage` withholds successful tool results from model messages when `contentMetadata.modelHandoffAllowed=false`: `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java:283`, `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java:570`.

### RAG launcher gate

- Top-level `rag-index` now uses `RagService.reindex(...)`: `src/main/java/dev/talos/cli/launcher/RagIndexCmd.java:34`, `src/main/java/dev/talos/cli/launcher/RagIndexCmd.java:42`.
- Private-mode refusal is now shared with the service-layer RAG policy instead of being reimplemented in the launcher.

### RAG extracted-document policy enforcement

- `Indexer.parseIndexableText(...)` now refuses extracted document text when `PrivateDocumentPolicy.ragIndexAllowed(...)` is false.
- `IndexingStats` now tracks privacy skips separately from ordinary skipped files.
- Index metadata schema now includes a privacy-config hash, so changing `privacy.document_extraction.allow_rag_indexing` makes the old index stale instead of silently serving old extracted chunks.

### Privacy UX and config visibility

- `Config.ensureDefaults()` and `default-config.yaml` now explicitly include `privacy.document_extraction.allow_send_to_model=false`, `persist_raw_artifacts=false`, and `allow_rag_indexing=false`.
- `/privacy status` now reports private-mode document-extraction model-context opt-in, raw artifact persistence, and RAG indexing separately from protected-read controls.

### Artifact scanner canary class

- `ArtifactCanaryScanner` now has a deterministic private-document fact canary class for tests/live-audit artifacts. This is not general PII detection; it proves that the scanner can catch ordinary private-document fixture facts, not only token-shaped secrets.

### Runtime artifact sink sanitizer

- `ProtectedContentPolicy` now owns the deterministic private-document fact canary class instead of leaving it scanner-only.
- `PromptDebugInspector`, `JsonSessionStore`, `JsonTurnLogAppender`, `MemoryUpdateListener`, and `TraceRedactor` already route their persisted strings through `ProtectedContentPolicy.sanitizeText(...)` or helpers backed by it, so these sinks now redact configured ordinary private-document fixture facts in the covered tests.
- This is a release-evidence guard for fixture facts, not a general natural-language PII classifier.

### Final-answer suppression after withheld private content

- `LoopState` now records when a tool result was withheld from model context by protected-read or private-document policy.
- `ToolCallLoop` sanitizes the final model answer only when runtime withheld content from model context during that loop. This keeps developer/default approved protected-read risk explicit while preventing a model-authored final answer from restating configured private-document fact canaries after a private-mode withheld extraction.
- `ToolCallExecutionStage` sets this flag for approved protected reads withheld by scope policy and for successful tool results whose `ToolContentMetadata.modelHandoffAllowed=false`.

## 5. Tests added or strengthened

- `private_mode_document_extraction_is_not_model_handoff_by_default`: `src/test/java/dev/talos/core/extract/DocumentExtractionServiceTest.java:79`.
- `private_mode_docx_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java:138`.
- `private_mode_xlsx_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java:177`.
- `privateModeDocxSendToModelStillCarriesPrivateDocumentMetadata`: `src/test/java/dev/talos/tools/impl/ReadFileToolTest.java:227`.
- `rag_index_command_refuses_private_mode_when_rag_disabled`: `src/test/java/dev/talos/cli/launcher/RagIndexCmdPrivateModeTest.java:20`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_pdfNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_docxNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateMode_ragEnabled_privateDocRagIndexingFalse_xlsxNotIndexed`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `privateDocumentRagIndexingPolicyChangeMarksOldIndexDirtyAndRebuildsWithoutPrivateChunks`: `src/test/java/dev/talos/core/index/IndexerPrivateDocumentPolicyTest.java`.
- `private_document_extraction_privacy_defaults_are_explicit_and_safe`: `src/test/java/dev/talos/core/ConfigPrivacyDefaultsTest.java`.
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
- `private_mode_pdf_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`.
- `private_mode_xls_extraction_is_withheld_from_model_context`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`.
- `private_mode_withheld_document_final_answer_redacts_model_fabricated_private_fact`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`.
- `private_mode_document_send_to_model_opt_in_allows_model_handoff`: `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`.

The RAG launcher test was observed red first: it failed while `RagIndexCmd` called `Indexer` directly. It passed after routing through `RagService.reindex(...)`.

## 6. Focused verification run

Passed:

```text
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.cli.launcher.RagIndexCmdPrivateModeTest" --no-daemon
```

Broader focused slice passed:

```text
./gradlew.bat test --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
```

Additional focused private-document provenance slice passed:

```text
./gradlew.bat test --tests "*IndexerPrivateDocumentPolicyTest" --tests "*ConfigPrivacyDefaultsTest" --tests "*PrivacyCommandTest" --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
```

Full deterministic gate passed:

```text
./gradlew.bat clean check e2eTest --no-daemon
```

Targeted generated-artifact canary scan passed:

```text
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
```

This pass also red-tested and then green-tested the ordinary private-document fact sink suite. The red run failed in prompt-debug, provider-body JSON, session snapshot, turn JSONL, local trace JSON, conversation memory, log sanitizer, and trace redaction before the central sanitizer patch. After the patch, this command passed:

```text
./gradlew.bat test --tests "*PromptDebugInspectorPrivateDocumentTest" --tests "*SensitiveLogRedactionTest" --tests "*MemoryUpdateListenerTest" --tests "*JsonSessionStoreTest" --tests "*JsonTurnLogAppenderTest" --tests "*TraceRedactorTest" --no-daemon
```

The wider privacy/artifact regression slice passed:

```text
./gradlew.bat test --tests "*ArtifactCanary*" --tests "*PromptDebug*" --tests "*JsonSessionStore*" --tests "*JsonTurnLogAppender*" --tests "*MemoryUpdateListener*" --tests "*TraceRedactor*" --tests "*SensitiveLog*" --tests "*ProtectedReadScope*" --tests "*IndexerPrivateDocumentPolicy*" --tests "*ConfigPrivacyDefaults*" --no-daemon
```

The full deterministic gate passed after updating stale canary expectations in extraction/indexer tests:

```text
./gradlew.bat clean check e2eTest --no-daemon
```

Post-clean artifact scans passed:

```text
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
```

Debug note: the broader slice initially exposed stale positive-indexing assertions that used the configured private-document fact canary as both a leak canary and a positive RAG indexing fact. The test fixture was split: blocked/leak tests keep private-document fact canaries, while positive explicit-indexing tests now use non-canary content. This preserves both invariants.

Additional model-loop provenance slice passed:

```text
./gradlew.bat test --tests "*ProtectedReadScopeIntegrationTest" --no-daemon
```

Additional local-display UX and workspace-boundary slice passed:

```text
./gradlew.bat test --tests "dev.talos.cli.repl.slash.InfraCommandsTest$Show" --no-daemon
```

This slice red-tested and fixed `/show` direct file fallback path escapes. Before the fix,
`/show ../outside.txt` could read a sibling file outside the workspace if it existed.
`ShowCommand` now normalizes the workspace and target path and rejects direct file fallback
outside the workspace before reading.

The same slice added and covered local display for extractable PDF/DOCX/XLS/XLSX documents
through `/show`. This is not a model handoff path; the command formats extracted safe text
locally and labels the output as `/show local display`.

Note: running `checkRuntimeArtifactCanaries` without `-PartifactScanRoots=...` failed by design because the task requires explicit scan roots and refuses to scan stale ignored manual-audit directories accidentally.

## 7. What is working now

- Private-mode document extraction sets `modelHandoffAllowed=false` by default for extracted documents.
- `ToolResult` preserves enough metadata for the model-loop boundary to act on extraction privacy decisions.
- The model-loop message receives a truthful withheld-content placeholder instead of raw private extracted text.
- The withheld placeholder no longer reuses protected-path wording for ordinary private extracted documents.
- Explicit send-to-model opt-in for extracted private documents does not erase the private-document metadata class.
- Top-level `rag-index` no longer bypasses the `RagService` private-mode indexing refusal.
- Indexer-level extraction now honors private-document RAG indexing policy, not only launcher/service-level private-mode refusal.
- Privacy-config changes now invalidate old indexes; an index built while private-document RAG indexing was allowed is no longer current after that opt-in is disabled.
- `/privacy status` exposes private-mode document-extraction opt-ins separately from protected-read scope.
- The artifact scanner can detect configured ordinary private-document fact canaries and redact those snippets in findings.
- Runtime sanitization now redacts the same configured ordinary private-document fact canaries before prompt-debug rendering, provider-body rendering, session snapshots, turn JSONL, local trace JSON, memory persistence, and log/trace helpers in deterministic tests.
- Private-mode PDF, DOCX, XLS, and XLSX extraction handoff is now covered by model-loop tests.
- A scripted model final answer that tries to restate a configured private-document fact canary after withheld extraction is redacted.
- Private-mode document-extraction `allow_send_to_model=true` is covered with non-canary content and confirms model handoff is allowed when explicitly configured.
- `/show` direct file fallback now rejects workspace escapes before reading local files.
- `/show` can display extracted PDF/DOCX/XLS/XLSX safe text locally and marks the output as not used for model context.

## 8. What is still not proven

- General PII redaction for arbitrary private documents. The current deterministic private-document fact canary class is evidence instrumentation, not a broad personal-data detector.
- End-to-end live Talos extraction artifact safety after a real model turn using PDF/DOCX/XLS/XLSX private fact fixtures.
- General final-answer suppression for arbitrary private facts. The deterministic test proves configured canary suppression only.
- Per-turn explicit send-to-model approval UX for extracted documents. Current evidence covers config opt-in, not an interactive approval scope.
- Dirty historical extracted-document RAG indexes containing ordinary private facts from pre-metadata or manually corrupted stores are partially covered by stale-index rebuild tests, but still need live-audit artifact evidence.
- Full private-document live audit using ordinary private facts rather than only token/canary-shaped secrets.
- `/show` local-display extraction still needs live manual evidence with real fixture files and terminal output capture; deterministic tests cover PDF/DOCX/XLS/XLSX safe-text extraction only.

## 9. Release impact

This pass improves the private-document architecture, but it does not make Talos private-document beta-ready.

Allowed claim after this pass:

- In private mode, successful DOCX/XLSX extraction results are not handed back into model context by default in the covered tests.
- In private mode, extracted PDF/DOCX/XLSX text is not indexed when private-mode RAG is enabled but `privacy.document_extraction.allow_rag_indexing=false`, in the covered tests.
- `/privacy status` now makes private document extraction opt-ins visible.
- Deterministic runtime artifact sink tests now prove configured ordinary private-document fact canaries are redacted across prompt-debug/provider-body rendering, session snapshots, turn JSONL, local trace JSON, memory persistence, and log/trace sanitizer helpers.
- Deterministic model-loop tests now cover private-mode PDF/DOCX/XLS/XLSX withholding, final-answer canary suppression after withheld extraction, and config-level document send-to-model opt-in.
- `/show` direct file fallback does not read outside the workspace in the covered test.
- `/show` provides a local-display-only PDF/DOCX/XLS/XLSX extraction path in the covered tests.

Forbidden claims after this pass:

- safe for tax folders
- safe for health records
- safe for legal/family/admin folders
- guarantees arbitrary extracted private document facts enter no persisted artifacts
- fully private-document beta-ready
- image/OCR beta support
- PowerPoint beta support

## 10. Next required slice

The next hard slice is live private-document provenance audit and remaining UX coverage:

1. Run a private-document live audit with ordinary private facts in PDF/DOCX/XLS/XLSX fixtures.
2. Capture `/last trace`, `/prompt-debug last`, prompt-debug save output, provider-body JSON, session/turn JSONL, local trace JSON, diffs, logs, and targeted artifact scan roots.
3. Confirm final answers do not fabricate private facts from withheld extraction in live model runs, not only scripted tests.
4. Add trace/status assertions for config-level extracted-document send-to-model opt-in, and design a per-turn approval UX if private-document beta requires it.
5. Package a manual-test runbook so a human tester can reproduce the private-document scenario without relying on Codex memory.

Do not start broad `AssistantTurnExecutor` cleanup before this artifact boundary is proven.
