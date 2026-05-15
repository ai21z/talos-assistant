# Next Release Hardening Verification

Branch: `v0.9.0-beta-dev`
Verified: 2026-05-15

## 1. What is already fixed

- Indirect grep/retrieve path protection exists for the tested boundary.
  - `src/main/java/dev/talos/tools/impl/GrepTool.java` skips `ProtectedContentPolicy.isProtectedPath(...)` matches and redacts matching lines through `ProtectedContentPolicy.sanitizeSearchLine(...)`.
  - `src/main/java/dev/talos/cli/repl/slash/GrepCommand.java` applies the same protected-path and unsupported-format skip/report policy.
  - `src/main/java/dev/talos/tools/impl/RetrieveTool.java` omits protected snippets and redacts secret/canary content from non-protected snippets.
  - `src/main/java/dev/talos/core/rag/RagService.java` skips protected snippet paths and sanitizes snippet text before returning prepared retrieval results.
- RAG indexing now excludes protected and unsupported files at code level in `src/main/java/dev/talos/core/index/Indexer.java`, not only through `default-config.yaml`.
- `src/main/resources/config/default-config.yaml` removes `.env` from includes and adds protected and unsupported-format excludes.
- `src/main/java/dev/talos/runtime/policy/ProtectedContentPolicy.java` centralizes current canary/private-marker/secret-like assignment redaction.
- Prompt-debug, provider-body display, trace redaction, and JSON session persistence delegate to `ProtectedContentPolicy` in the tested paths.
- Unsupported-format classification is centralized through `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`, with `UnsupportedDocumentFormats` delegating to it.

## 2. What is still open

- Approved direct protected reads intentionally preserve raw output into model context.
- There is no scoped protected-read approval mode such as local-display-only versus send-to-model.
- Runtime logs can still include raw tool parameters, path hints, raw result summaries, exception messages, retrieval traces, and command diagnostics.
- Artifact canary scanning is documented/manual, not a Gradle/CI gate.
- RAG index metadata does not record privacy policy version, file-capability policy version, config hash, or staleness.
- Unsupported-format final-answer truthfulness is not fully runtime-enforced for summarize/compare flows with a bad scripted model.
- Private-folder mode does not exist.
- The two-model prompt-bank live audit has not been run in this branch state.
- `Config.ensureDefaults()` has older fallback RAG excludes than `default-config.yaml`; users missing config sections may not receive the full protected/unsupported default exclude set.

## 3. Whether approved protected read output can reach model context

Yes.

Evidence:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
  - `rawResult = turnProcessor.executeTool(...)`
  - `shouldPreserveApprovedProtectedReadResult(...)` returns true for successful `talos.read_file` calls whose `pathHint` is protected by `ProtectedPathPolicy`.
  - When true, `result = rawResult` and `ToolCallSupport.formatToolResult(effective, result, true)` preserves raw output.
  - `appendResultMessage(...)` then appends that raw protected read result back into the model-loop messages.
- `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
  - `formatToolResult(..., preserveSuccessOutput = true)` bypasses `ProtectedContentPolicy.sanitizeText(...)`.

Conclusion: approved direct protected reads are intentionally allowed to feed raw protected content to the model in the current default path. This is not safe enough for private-document mode.

## 4. Whether debug/runtime logs can contain raw tool parameters

Yes.

Evidence:

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
  - `LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());`
  - This can log raw grep patterns, read paths, edit/write content snippets, and canary-like tool arguments.
  - `LOG.debug("  Tool {} -> {}", ..., ToolCallSupport.truncateForLog(result.output()))` can log raw output when `result` is deliberately preserved for approved protected reads.
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
  - Approval details include raw `path`, content preview, `old_string`, and `new_string` in `buildApprovalDetail(...)`.
  - Exception logging uses raw exception messages in warnings.
- `src/main/java/dev/talos/core/rag/RagService.java`
  - `LOG.debug("Retrieval pipeline trace:\n{}", trace.summary())` and failure logs are not centrally redacted.
- `src/main/java/dev/talos/core/index/Indexer.java`
  - Logs full root paths and skipped file paths/errors without protected-path formatting.
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
  - Logs malformed payload JSON in debug paths.

Conclusion: there is no central safe log formatter yet. Logging needs a redaction utility and focused tests.

## 5. Whether unsupported-format final-answer truthfulness is runtime-enforced or only documented/tested partially

Partially tested, not fully runtime-enforced.

Evidence:

- Direct read/write enforcement exists:
  - `ReadFileTool` rejects unsupported formats via `UnsupportedDocumentFormats.isUnsupported(...)`.
  - `FileWriteTool` rejects unsupported writes via `UnsupportedDocumentFormats.writeCapabilityMessage(...)`.
  - `ParserUtil.smartParse(...)` rejects unsupported and binary-looking files.
- Search/index enforcement exists:
  - `GrepTool`, slash `GrepCommand`, and `Indexer` skip/report unsupported formats.
- Runtime final-answer postconditions are incomplete:
  - `ToolCallExecutionStage.IterationOutcome.unsupportedReadPathsThisIteration` records unsupported read paths.
  - Existing tests cover direct unsupported-doc stops and some search/index behavior.
  - There is no broad final-answer override that catches a scripted model saying "I reviewed report.docx" after an unsupported read/search/retrieve limitation.

Conclusion: unsupported format truthfulness is improved but not yet proven against bad model final answers in summarize/compare flows.

## 6. Whether RAG index invalidation/versioning exists

No.

Evidence:

- `src/main/java/dev/talos/core/index/Indexer.java` writes Lucene chunks but no index metadata with privacy/file capability policy versions.
- `src/main/java/dev/talos/core/rag/RagService.java` calls `ensureIndexExists(ws)` and opens `LuceneStore`, but there is no privacy-policy metadata check before retrieval.
- `ProtectedContentPolicy` and `FileCapabilityPolicy` currently have no `POLICY_VERSION` constants.

Conclusion: retrieval-time sanitization is defense-in-depth, but old dirty indexes can remain on disk without explicit invalidation or rebuild/refusal semantics.

## 7. Whether artifact canary scanning is automated in tests/CI or only self-reported

Only self-reported/manual.

Evidence:

- `work-cycle-docs/reports/t267-and-file-format-release-gate.md` records a manual `rg` canary scan command.
- Search found no `ArtifactCanaryScanTest`, no `checkNoSensitiveCanaries` Gradle task, and no T275 canary scan test class.

Conclusion: artifact scanning is not CI-grade yet.

## 8. Whether `alex000kim-article.txt` exists in the repo

Absent from the repo workspace.

Evidence:

- Recursive search for `alex000kim-article.txt`, `local coding assistant Source Leak`, `KAIROS`, `bashSecurity`, and `promptCacheBreakDetection` only found the previous note in `work-cycle-docs/reports/t267-source-crosscheck.md`.

Conclusion: do not claim the article was inspected. If project policy requires it, add a ticket or source artifact request.

## 9. Post-implementation verification note

This memo records the pre-implementation state inspected at the start of the hardening pass. The follow-up changes are summarized in `work-cycle-docs/reports/next-beta-readiness-hardening-report.md` and `work-cycle-docs/reports/t267-and-file-format-release-gate.md`.

Fresh verification after implementation:

- `./gradlew.bat test --tests "*ProtectedReadScopePolicyTest" --tests "*ProtectedReadScopeIntegrationTest" --tests "*SensitiveLogRedactionTest" --tests "*ArtifactCanaryScanTest" --tests "*IndexerPolicyMetadataTest" --tests "*UnsupportedFinalAnswerTruthfulnessTest" --no-daemon` - passed.
- `./gradlew.bat test --tests "dev.talos.app.ui.TerminalFirstRunTest" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed.
