# T295 - Extraction Privacy and Artifact Boundary

Status: partial implementation, open
Severity: P0 for beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-17
Owner: unassigned

## Problem

Adding document extraction multiplies the amount of sensitive text Talos can produce. Extraction output must be treated as tool output and artifact content, not as harmless parser internals.

## Evidence from current code

- Tool results are sanitized centrally unless explicitly preserved: `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java:275`, `:279`, `:283`.
- `ToolCallSupport.formatToolResult(...)` sanitizes tool output by default: `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java:74`, `:80`, `:93`.
- `ProtectedContentPolicy.sanitizeText(...)` and `sanitizeToolResult(...)` exist: `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java:44`, `:124`.
- Runtime artifact scanning exists: `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java:49`; Gradle exposes `checkRuntimeArtifactCanaries`: `build.gradle.kts:420`.

## Evidence from source crosscheck

OpenAI and Gemini agent/tool-loop documentation support the same boundary: tool outputs are sent back to the model or used to form final answers. OWASP logging guidance supports masking or excluding sensitive information in logs.

## User impact

Users will assume local extraction is safe for private paperwork unless Talos proves that extracted text cannot leak through model context, logs, traces, prompt-debug, provider-body captures, sessions, or RAG indexes.

## Product risk

P0. A single raw extraction leak would invalidate private-document positioning.

## Runtime boundary affected

Extraction service, direct reads, grep/search, RAG indexing, retrieve, prompt-debug, provider-body captures, traces, sessions, logs, command output, final answers, and audit reports.

## Non-goals

- No encrypted local artifact store in this ticket.
- No raw diagnostic persistence by default.

## Required behavior

- Extracted text is sanitized before model context by default.
- Private mode approved protected reads remain `LOCAL_DISPLAY_ONLY` unless explicitly opted into model context.
- Extracted raw text is never persisted to prompt-debug, provider-body captures, traces, sessions, or logs by default.
- Artifact canary scan includes extraction-generated artifacts.
- Extraction logs include metadata/status only, not raw content.

## Proposed implementation

Adapters may produce raw parser text internally, but the public `DocumentExtractionResult` should expose safe text only. If raw text exists, it must use a non-serializable internal type and be discarded before the result reaches tools, RAG, traces, sessions, prompt-debug, or logs.

Before any consumer sees text, route through a single policy method such as `ProtectedContentPolicy.sanitizeExtractionResult(...)`. Add explicit unsafe debug hooks only behind maintainer config and keep them disabled by default.

## Tests

- `pdf_extraction_provider_body_does_not_contain_file_discovered_canary`
- `docx_extraction_prompt_debug_does_not_contain_file_discovered_canary`
- `xlsx_extraction_trace_does_not_contain_file_discovered_canary`
- `image_ocr_session_log_does_not_contain_file_discovered_canary`
- `extraction_logs_do_not_contain_raw_extracted_text`
- `private_mode_extraction_local_display_only_blocks_model_handoff`
- `artifact_scan_covers_extraction_output_dirs`
- `document_extraction_result_serialization_omits_raw_text`
- `unsafe_raw_extraction_debug_requires_explicit_maintainer_config`

## Acceptance criteria

- Raw extracted canaries appear only in source fixture files and allowlisted test sources.
- Artifact scan passes after extraction test runs and live audit.
- No extraction adapter logs raw content.
- No public extraction DTO, trace DTO, or session DTO serializes raw text.

## 2026-05-17 update

Partial runtime boundary work landed for model-context handoff:

- `ToolResult` now carries `ToolContentMetadata`, preserving privacy class, source, model-handoff, artifact-persistence, and RAG-indexing decisions.
- `PrivateDocumentPolicy` now makes private-mode extracted document text local-display-only by default.
- `ReadFileTool` preserves extraction metadata when returning successful document extraction output.
- `ToolCallExecutionStage` withholds any successful tool result whose metadata says `modelHandoffAllowed=false` before appending the tool result to model-loop messages.
- Top-level `rag-index` now routes through `RagService.reindex(...)`, closing the launcher bypass for private-mode indexing.
- `Indexer` now enforces private-document RAG indexing policy directly and records privacy skips.
- Index metadata now hashes privacy config, so private-document RAG indexing opt-in changes invalidate prior indexes.
- `Config.ensureDefaults()` and `default-config.yaml` now expose `privacy.document_extraction` defaults explicitly.
- `/privacy status` now reports private document extraction opt-ins.
- `ArtifactCanaryScanner` now includes a deterministic ordinary private-document fact canary class for test/live-audit artifact scans.
- `ProtectedContentPolicy` now owns that deterministic private-document fact canary class centrally, so runtime artifact sinks that already call `sanitizeText(...)` redact the same fixture facts instead of relying only on scanner findings.

Focused tests passed on 2026-05-17:

```text
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.cli.launcher.RagIndexCmdPrivateModeTest" --no-daemon
./gradlew.bat test --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
./gradlew.bat test --tests "*IndexerPrivateDocumentPolicyTest" --tests "*ConfigPrivacyDefaultsTest" --tests "*PrivacyCommandTest" --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
```

Additional red-green artifact-sink proof passed on 2026-05-17:

```text
./gradlew.bat test --tests "*PromptDebugInspectorPrivateDocumentTest" --tests "*SensitiveLogRedactionTest" --tests "*MemoryUpdateListenerTest" --tests "*JsonSessionStoreTest" --tests "*JsonTurnLogAppenderTest" --tests "*TraceRedactorTest" --no-daemon
./gradlew.bat test --tests "*ArtifactCanary*" --tests "*PromptDebug*" --tests "*JsonSessionStore*" --tests "*JsonTurnLogAppender*" --tests "*MemoryUpdateListener*" --tests "*TraceRedactor*" --tests "*SensitiveLog*" --tests "*ProtectedReadScope*" --tests "*IndexerPrivateDocumentPolicy*" --tests "*ConfigPrivacyDefaults*" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
```

The red run failed before the sanitizer patch in prompt-debug markdown, provider-body JSON, session snapshots, turn JSONL, local trace JSON, memory persistence, log sanitizer, and trace redaction. After the patch, the same suite passed.

This still does not close the ticket. Remaining P0 work is live-audit proof using real Talos turns and ordinary private facts, final-answer suppression when a model tries to restate withheld private facts, explicit send-to-model UX/tracing for extracted documents, and broader PDF/XLS model-loop coverage. The deterministic private-document fact canary class is evidence instrumentation, not general PII detection. Positive RAG indexing tests now use non-canary content so they do not conflict with the leak-detection canary class.

Follow-up model-loop provenance tests passed on 2026-05-17:

```text
./gradlew.bat test --tests "*ProtectedReadScopeIntegrationTest" --no-daemon
```

New coverage:

- PDF private-mode extraction is withheld from model context.
- XLS private-mode extraction is withheld from model context.
- A scripted model final answer that tries to restate a configured private-document fact canary after withheld extraction is redacted.
- Config-level `privacy.document_extraction.allow_send_to_model=true` allows document extraction handoff with non-canary content.

Follow-up local-display and workspace-boundary tests passed on 2026-05-17:

```text
./gradlew.bat test --tests "dev.talos.cli.repl.slash.InfraCommandsTest$Show" --no-daemon
```

New coverage:

- `/show` direct file fallback rejects `../` workspace escapes before reading local files.
- `/show` can extract PDF/DOCX/XLS/XLSX text for local display without using model context.
- The local-display path uses the safe extracted text path and redacts configured private-document fact canaries.

Remaining P0 work is now live-audit proof using real Talos turns and ordinary private facts, per-turn explicit send-to-model UX/tracing for extracted documents, and final manual-test packaging. The deterministic final-answer test is not a general PII filter.

## Rollback / migration notes

If any artifact leak appears, keep the relevant extractor disabled and revert that format to honest unsupported behavior.

## Open questions

- Do we need an in-memory raw extraction type that cannot accidentally serialize through Jackson?

## Related files

- `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java`
- `src/main/java/dev/talos/cli/prompt/PromptDebugInspector.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
