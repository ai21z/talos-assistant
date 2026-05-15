# Next Pass Verification

Branch: `v0.9.0-beta-dev`
Verified: 2026-05-15

## 1. Protected read scope status

`ProtectedReadScopePolicy` exists in `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java`.

Evidence:

- `ProtectedReadScopePolicy.ProtectedReadScope` defines `LOCAL_DISPLAY_ONLY` and `SEND_TO_MODEL_CONTEXT`.
- `ProtectedReadScopePolicy.defaultScope(Config)` returns `LOCAL_DISPLAY_ONLY` when `privacy.mode = private` unless overridden.
- `ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(Config)` only allows private-mode model handoff when scope is `SEND_TO_MODEL_CONTEXT` and `privacy.protected_read.allow_send_to_model = true`.
- `ProtectedReadScopePolicy.persistRawArtifacts(Config)` defaults false.
- `ToolCallExecutionStage` checks successful protected `talos.read_file` calls and, when model handoff is not allowed, replaces raw tool output with: protected content was read after approval but withheld from model context.
- `ToolCallSupport.formatToolResult(..., preserveSuccessOutput)` still supports developer/default approved protected read handoff when the policy allows it.
- `TurnProcessor.buildApprovalDetail(...)` adds the protected-read scope note to local approval prompts.

Answers:

1. Does `ProtectedReadScopePolicy` exist? Yes.
2. Does private/strict mode default approved protected reads to `LOCAL_DISPLAY_ONLY`? Yes for `privacy.mode = private`.
3. Does developer/default mode still allow approved protected reads into model context? Yes. This is explicit developer-mode risk.
4. Does `ToolCallExecutionStage` withhold approved protected read output from model messages in private mode? Yes for the tested tool-loop path.
5. Does `ProtectedReadScopeIntegrationTest` exist? Yes, at `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`. It currently covers private local-display-only handoff, but not developer explicit-risk behavior, private send-to-model opt-in, or persistence redaction under opt-in.

## 2. Privacy, logging, and artifact status

Evidence:

- `ProtectedContentPolicy` centralizes canary, private marker, secret-like assignment, protected path-like string, tool-parameter, map, and log sanitization.
- `ArtifactCanaryScanner` exists and scans text-like artifact files.
- `ProcessCommandRunner` delegates command stdout/stderr redaction to `ProtectedContentPolicy`.
- `TraceRedactor`, `JsonSessionStore`, `JsonTurnLogAppender`, and `PromptDebugInspector` use central redaction in existing tested paths.

`ArtifactCanaryScanner` skips:

- directory names: `classes`, `generated`, `.gradle`, `.cache`
- any path under `build/resources`
- any path under `local/manual-testing`
- any path under `local/manual-workspaces`

Answer:

6. Does `ArtifactCanaryScanner` run through `check`? Yes. It is a JUnit test path through `ArtifactCanaryScanTest`, and `./gradlew.bat clean check e2eTest --no-daemon` passed in the previous pass.
7. Which directories does it skip? Listed above.
8. Are skipped directories justified? Partially. Skipping compiled/generated build infrastructure is reasonable. Skipping ignored manual audit folders avoids historical dirty local false positives, but those folders can contain generated prompt-debug/provider-body/trace/session artifacts and must be scanned separately by live-audit/release scripts.

## 3. Logging audit findings

Raw/risky log sites still exist and need audit/fix or explicit tickets:

- `ToolCallExecutionStage`: some path hints and read signatures are logged without `sanitizeForLog`.
- `ToolCallParser`: malformed tool payload JSON is logged raw at debug level.
- `RagService`: retrieval trace summary and lazy-index failure messages are logged without central redaction.
- `Indexer`: indexing root, skipped paths, stale/corrupt index errors, and per-file failures are logged without protected-path formatting.
- `JsonSessionStore`: session ids, trace paths, and exception messages are logged without a safe formatter.
- `JsonTurnLogAppender`: exception messages are logged without a safe formatter.
- `OllamaChatClient`, `CompatChatClient`, and embedding providers log provider/transport errors; they do not appear to log full provider bodies in the inspected grep output, but exception messages still need redaction review.
- `AssistantTurnExecutor` logs engine, retry, and handoff exceptions; many are not sanitized.

Answer:

13. Are there raw LOG call sites still emitting unsanitized tool params/results/exception messages? Yes. Tool parameter logging in `ToolCallExecutionStage` is improved, but the broader logging surface is not complete.

## 4. RAG status

Evidence:

- `Indexer.policyMetadataFile(root)` writes `talos-index-metadata.json`.
- `Indexer.isPolicyMetadataCurrent(root)` checks schema, privacy policy version, file-capability policy version, and RAG config hash.
- `Indexer.invalidateIndex(root)` removes old index directories.
- `RagService.ensureIndexExists(...)` invalidates/rebuilds when metadata is missing/stale or the index is corrupt.
- `RagService` skips indexing/retrieval when private mode disables RAG by default.

Answer:

9. Does RAG metadata versioning exist and does stale metadata rebuild/refuse? Yes. Metadata V1 exists and stale/missing metadata triggers invalidation/rebuild. Broader e2e coverage is still missing.

## 5. Config fallback status

Evidence:

- `default-config.yaml` excludes `.env`, `.env.*`, `*.env`, `secrets/**`, `.ssh/**`, `.aws/**`, `.azure/**`, `.gnupg/**`, `.config/gcloud/**`, `protected/**`, PDF/Office/image/archive/binary families, plus extra repo/build folders such as `.vscode`, `.claude`, `.gradle`, `.mvn`, `node_modules`, `dist`, `prompts`, and `META-INF`.
- `Config.ensureDefaults()` includes the core protected and unsupported excludes, but does not include all extra resource-default repo/build excludes.
- `Config.ensureDefaults()` sets `privacy.mode = developer`, protected-read scope defaults, and private-mode RAG disabled.

Answer:

10. Does `Config.ensureDefaults` match `default-config.yaml` protected/unsupported excludes? It matches the critical protected and unsupported format families, but does not fully match every resource-default exclude. Add parity tests and either fix or document the intentional differences.

## 6. Unsupported-format truthfulness status

Evidence:

- `FileCapabilityPolicy` classifies PDF, Word, Excel, PowerPoint, images/scans, archives, compiled/executable artifacts, and generic binaries as unsupported.
- `UnsupportedDocumentFormats` delegates to `FileCapabilityPolicy`.
- `ReadFileTool` rejects unsupported formats.
- `FileWriteTool` rejects unsupported writes.
- `AssistantTurnExecutor` has final-answer repair logic for unsupported document read paths.
- `UnsupportedFinalAnswerTruthfulnessTest` currently covers DOCX summary fabrication and XLSX-vs-text compare fabrication.

Answers:

11. Which unsupported-format final-answer scenarios are tested? DOCX summary and XLSX compare-to-text scripted model fabrication.
12. Which unsupported-format families remain untested? PDF, PowerPoint, images/scans, archives, generic binaries, PDF/image/archive compare flows, unsupported search "no matches" claims, and unsupported PDF/DOCX creation/write redirects.

## 7. Source and live-audit status

Answer:

14. Was `alex000kim-article.txt` present in the repo? No. Recursive search for `alex000kim-article.txt`, `Claude Code Source Leak`, `KAIROS`, `bashSecurity`, and `promptCacheBreakDetection` only found prior report/ticket notes.
15. Was the two-model live audit actually run? No. `work-cycle-docs/reports/t267-live-two-model-audit.md` remains a runbook/status document, not an executed audit result.

## 8. Immediate next gaps

- Expand `ProtectedReadScopeIntegrationTest` to cover developer risk, private opt-in denial, private opt-in allowance, and persistence redaction.
- Add user-facing `/privacy` command and register it.
- Add warning-only sensitive-folder detection.
- Strengthen artifact scanner targeted runtime artifact tests and coverage report.
- Complete log redaction audit and fix highest-risk raw logs.
- Add config fallback parity tests.
- Broaden unsupported-format final-answer tests.
- Add realistic RAG dirty-index integration/e2e coverage where practical.
- Attempt live two-model audit or record exact unavailable dependencies.

## 9. Post-verification update from this pass

Implemented after the verification memo:

- Expanded `ProtectedReadScopeIntegrationTest` for private local-display-only, developer/default explicit risk, private send-to-model opt-in denial, private send-to-model opt-in allowance, and persistence redaction.
- Added `/privacy status`, `/privacy private on`, `/privacy private off`, and `/privacy help`.
- Added warning-only `SensitiveWorkspaceDetector`.
- Added targeted runtime artifact scans for prompt-debug, provider body, session, trace, turn JSONL, command-output artifacts, and generated reports.
- Added `SafeLogFormatter` and focused log-redaction tests.
- Added config fallback parity tests and updated `Config.ensureDefaults`.
- Broadened unsupported-format final-answer tests across PDF, Word/DOCX, Excel/XLSX, PowerPoint/PPTX, images, archives, binaries, compare, search, and write/create flows.
- Added Lucene-backed `RagDirtyIndexIntegrationTest`.
- Attempted live-audit dependency check; `ollama list` crashed with access violation `0xc0000005`, and local config showed GPT-OSS only, not the required Qwen/GPT-OSS pair.
