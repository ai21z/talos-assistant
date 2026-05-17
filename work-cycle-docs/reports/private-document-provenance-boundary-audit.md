# Private Document Provenance Boundary Audit

Date: 2026-05-17
Branch: `v0.9.0-beta-dev`

## 1. Executive verdict

Private-document beta is still not release-ready.

This pass closes the first concrete model-context leak in the new document extraction path: private-mode extracted DOCX/XLSX-style document text is no longer treated as ordinary tool output when the tool result is appended back into the model loop.

Follow-up work in the same gate also closes the remaining RAG indexing policy hole found after review: `Indexer` now honors `PrivateDocumentPolicy.ragIndexAllowed(...)`, records privacy skips explicitly, and treats privacy-config changes as index-metadata changes. The fix remains partial because prompt-debug/session/trace/provider-body safety for ordinary private facts is still not fully proven end-to-end.

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

## 8. What is still not proven

- Prompt-debug markdown/provider-body JSON redaction for ordinary private document facts.
- Session JSON and turn JSONL redaction for ordinary private document facts.
- Local trace redaction for ordinary private document facts.
- Log redaction for ordinary private document facts that appear in exception paths or derived assistant text.
- Final-answer suppression when the model tries to fabricate private facts from a withheld extraction.
- Explicit send-to-model opt-in for extracted documents, separate from protected-path reads.
- PDF and legacy XLS model-loop provenance tests. DOCX and XLSX are covered in this pass; the policy is generic, but the evidence is not yet complete.
- Dirty historical extracted-document RAG indexes containing ordinary private facts from pre-metadata or manually corrupted stores are partially covered by stale-index rebuild tests, but still need live-audit artifact evidence.
- Full private-document live audit using ordinary private facts rather than only token/canary-shaped secrets.

## 9. Release impact

This pass improves the private-document architecture, but it does not make Talos private-document beta-ready.

Allowed claim after this pass:

- In private mode, successful DOCX/XLSX extraction results are not handed back into model context by default in the covered tests.
- In private mode, extracted PDF/DOCX/XLSX text is not indexed when private-mode RAG is enabled but `privacy.document_extraction.allow_rag_indexing=false`, in the covered tests.
- `/privacy status` now makes private document extraction opt-ins visible.

Forbidden claims after this pass:

- safe for tax folders
- safe for health records
- safe for legal/family/admin folders
- guarantees no extracted private document facts enter persisted artifacts
- fully private-document beta-ready
- image/OCR beta support
- PowerPoint beta support

## 10. Next required slice

The next hard slice is provenance-aware artifact redaction:

1. Add ordinary private-fact canaries to prompt-debug/provider-body/session/trace tests.
2. Prove they fail where provenance is currently lost or assistant text persists derived content.
3. Add a provenance-aware artifact boundary or prevent raw private document text from entering all persisted message/artifact surfaces.
4. Run the private-document live audit and targeted runtime artifact scan over the audit output.

Do not start broad `AssistantTurnExecutor` cleanup before this artifact boundary is proven.
