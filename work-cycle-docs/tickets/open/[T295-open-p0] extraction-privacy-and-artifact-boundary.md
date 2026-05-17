# T295 - Extraction Privacy and Artifact Boundary

Status: open
Severity: P0 for beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
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

Focused tests passed on 2026-05-17:

```text
./gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.cli.launcher.RagIndexCmdPrivateModeTest" --no-daemon
./gradlew.bat test --tests "*DocumentExtraction*" --tests "*ProtectedReadScope*" --tests "*ReadFileTool*" --tests "*Rag*Dirty*" --tests "*IndexerPolicyMetadata*" --tests "*ArtifactCanary*" --no-daemon
./gradlew.bat clean check e2eTest --no-daemon
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/reports,build/test-results" --no-daemon
```

This does not close the ticket. Remaining P0 work is provenance-aware artifact safety for ordinary private facts in prompt-debug/provider-body captures, session snapshots, turn JSONL, local traces, logs, final answers, and live-audit artifacts. Secret/canary regex redaction is not enough for real private documents.

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
