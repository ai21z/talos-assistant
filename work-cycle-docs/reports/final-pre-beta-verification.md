# Final Pre-Beta Verification

Supersession note, 2026-05-18: this report captures an earlier pre-document-extraction verification pass. Current document-extraction and live-audit decisions must use `work-cycle-docs/reports/full-talos-capability-state-and-document-extraction-audit.md` plus the latest private-folder bank audit `capability-live-audit-20260518-004603`.

## 1. Scope

This report verifies the current `v0.9.0-beta-dev` branch before the final pre-beta evidence and hardening pass. It focuses on privacy UX, protected-read scope, sensitive workspace warnings, artifact scanning, log redaction, RAG dirty-index handling, config fallback safety, unsupported-format truthfulness, reports, and ticket freshness.

Existing local dirty files before this pass were not part of this verification: `CHANGELOG.md`, `gradle.properties`, untracked `AGENTS.md`, and untracked `work-cycle-docs/tickets/done/[T266-done-high] beta-candidate-identity-and-evidence-packet.md`.

## 2. Privacy UX and protected-read scope

- `/privacy` exists in `src/main/java/dev/talos/cli/repl/slash/PrivacyCommand.java`.
  - `PrivacyCommand` is declared at line 10.
  - `execute(...)` dispatches `status`, `help`, `private on`, and `private off` at lines 28-45.
  - `private on` calls `ProtectedReadScopePolicy.setPrivateMode(ctx.cfg(), true)` at line 40.
  - `private off` calls `ProtectedReadScopePolicy.setPrivateMode(ctx.cfg(), false)` at line 44.
- `/privacy` is registered in `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`.
  - `registry.register(new PrivacyCommand(workspace))` is present at line 406.
- Private-mode policy exists in `src/main/java/dev/talos/runtime/policy/ProtectedReadScopePolicy.java`.
  - `persistRawArtifacts(...)` is at lines 48-50.
  - `setPrivateMode(...)` is at lines 59-67 and mutates the active `Config` object.
  - Approved-read handoff notes distinguish `SEND_TO_MODEL_CONTEXT` and `LOCAL_DISPLAY_ONLY` at lines 72-76.
- Protected-read runtime enforcement exists in `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`.
  - The tool-result handoff path consults `ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(...)` before appending protected direct-read output back to model messages.
- Integration coverage exists in `src/test/java/dev/talos/runtime/toolcall/ProtectedReadScopeIntegrationTest.java`.
  - The class exists and covers private-mode local-display-only behavior, default/developer behavior, explicit private-mode send-to-model opt-in, denied reads, and persistence redaction.
- `/privacy` command coverage exists in `src/test/java/dev/talos/cli/repl/slash/PrivacyCommandTest.java`.
  - Existing tests cover status, private on/off, retrieve disabled in private mode, status workspace non-mutation, and help text.

Verification answers:

1. `/privacy` exists and works as documented at command/test level.
2. `/privacy` is registered in `TalosBootstrap`.
3. `/privacy private on/off` appears session/current-`Config` scoped. No writeback to `~/.talos/config.yaml` is present in `PrivacyCommand` or `ProtectedReadScopePolicy`.
4. README now says `/privacy` changes the current session/config state and does not write persistent defaults to `~/.talos/config.yaml`.
5. Developer/default mode still allows approved protected reads into model context as an explicit risk.
6. Private mode withholds approved protected reads from model context by default.
7. Protected-read integration tests cover both private/default behaviors.

## 3. Sensitive workspace detection

- `SensitiveWorkspaceDetector` exists in `src/main/java/dev/talos/runtime/policy/SensitiveWorkspaceDetector.java`.
  - Sensitive terms include the short term `id` at lines 13-15.
  - Folder matching currently uses `folderName.contains(term)` at lines 31-32.
  - Shallow workspace metadata inspection uses `Files.walk(root, 2)` at line 39.
  - Filename matching currently uses `fileName.contains(term)` at lines 57-58.
- Tests exist in `src/test/java/dev/talos/runtime/policy/SensitiveWorkspaceDetectorTest.java`.
  - Current coverage includes tax, health, `secrets/`, many private documents, content non-read behavior, warning copy, and non-sensitive code workspace.

Verification answers:

8. Sensitive workspace detection does not read file contents in the current implementation; it uses folder/file names and a shallow metadata walk.
9. Sensitive workspace detection now tokenizes short terms such as `id`, reducing false positives for ordinary names such as `valid-project` and `grid-ui` while preserving warnings for tokenized `id` folders.

## 4. Artifact scanning

- `ArtifactCanaryScanner` exists in `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java`.
  - Broad scan entrypoint is `scan(...)`.
  - Targeted runtime-artifact entrypoint is `scanRuntimeArtifacts(...)` at line 49.
  - Always-skipped directory names include `.git`, `.gradle`, `classes`, `generated`, `generated-sources`, `generated-test-sources`, and `jacoco` at lines 29-31.
  - Broad scans additionally skip `test-results`, `reports`, and `tmp` at line 33.
  - Broad scans skip `local/manual-testing` and `local/manual-workspaces` at lines 120-123.
- `ArtifactCanaryScanTest` exists in `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java`.
  - Targeted tests cover prompt-debug, provider-body, sessions, traces, turn JSONL, command output, generated reports, exact file/line reporting, and compiled class skipping.

Verification answers:

10. Targeted artifact scanning covers prompt-debug, provider-body, sessions, traces, turn JSONL, command output, and generated reports in unit tests.
11. Broad scans still skip generated/report/manual-audit directories to avoid fixture and build-output noise. Targeted runtime-artifact scans do not skip manual audit directories the same way, and `checkRuntimeArtifactCanaries` now provides a maintainer-facing task for completed live-audit artifact trees.

## 5. Logging

- `SafeLogFormatter` exists in `src/main/java/dev/talos/runtime/policy/SafeLogFormatter.java`.
- `SensitiveLogRedactionTest` exists in `src/test/java/dev/talos/runtime/policy/SensitiveLogRedactionTest.java`.
- Existing `work-cycle-docs/reports/log-redaction-audit.md` says focused log redaction improved but is not blanket proof.
- Remaining raw or insufficiently safe log paths found during source audit include:
  - `src/main/java/dev/talos/runtime/toolcall/ToolCallParser.java`: raw malformed tool-call JSON is logged at line 515.
  - `src/main/java/dev/talos/runtime/JsonSessionStore.java`: session ids, paths, and exception messages are logged without safe formatting at lines 54, 82, 119, 176, 210, 331, 351, 370, 379, and 536.
  - `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java`: exception messages are logged without safe formatting at lines 54 and 77.
  - `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`: multiple retry/failure exception details are logged from raw messages.
  - `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`: retry/failure exception messages are logged from raw messages.
  - `src/main/java/dev/talos/cli/modes/RagMode.java`: retrieval/indexing failure details and selected path/token diagnostics are logged without consistent safe formatting.
  - `src/main/java/dev/talos/core/rag/LuceneStore.java`: path and exception-message logs are not consistently safe-formatted.
  - `src/main/java/dev/talos/core/index/Indexer.java`: most recent paths use `SafeLogFormatter`, but residual failure paths still log `e.toString()` or throwable objects.
  - `src/main/java/dev/talos/core/rag/RagService.java`: retrieval/lazy-indexing failure logs include raw error reason/throwable paths in some places.

Verification answers:

12. Several `LOG.*` call sites still do not use `SafeLogFormatter` or `ProtectedContentPolicy`. T283 must remain open unless this pass converts or explicitly tickets every residual high-risk site.

## 6. Config fallback defaults

- `src/main/resources/config/default-config.yaml` contains protected RAG excludes for `.env`, `.env.*`, `*.env`, `secrets/**`, `.ssh/**`, `.aws/**`, `.azure/**`, `.gnupg/**`, `.config/gcloud/**`, and `protected/**` at lines 50-60.
- `src/main/resources/config/default-config.yaml` contains unsupported-format excludes for PDF/Office/image/archive/binary families at lines 90-118.
- `src/main/java/dev/talos/core/Config.java` fallback defaults include protected excludes at lines 226-229 and unsupported-format excludes at lines 237-240.
- `src/main/java/dev/talos/core/Config.java` fallback privacy defaults are present at lines 284-303.
- `src/test/java/dev/talos/core/ConfigPrivacyDefaultsTest.java` covers protected excludes, unsupported-format excludes, resource/fallback parity, missing user config defaults, and private-mode defaults.

Verification answers:

13. Config fallback defaults match the privacy-critical default-config patterns covered by `ConfigPrivacyDefaultsTest`. No divergence was found in the inspected protected/unsupported exclude families.

## 7. RAG dirty-index handling

- `src/main/java/dev/talos/core/index/Indexer.java` has policy metadata support.
  - `policyMetadataFile(...)` is at line 63.
  - `isPolicyMetadataCurrent(...)` checks schema/policy/config hash at line 67.
  - `invalidateIndex(...)` is at line 82.
  - Metadata writing happens after indexing and is implemented by `writePolicyMetadata(...)`.
- `src/main/java/dev/talos/core/rag/RagService.java` checks private-mode retrieval and stale metadata.
  - Private-mode retrieval is disabled unless explicitly enabled at lines 112 and 305.
  - `ensureIndexExists(...)` checks current metadata and invalidates stale/missing/corrupt metadata before retrieval.
- `src/test/java/dev/talos/core/index/IndexerPolicyMetadataTest.java` and `src/test/java/dev/talos/core/rag/RagDirtyIndexIntegrationTest.java` exist.

Verification answers:

15. RAG dirty-index coverage exercises real Lucene/index paths through `RagDirtyIndexIntegrationTest`, not only metadata unit tests.

## 8. Unsupported-format truthfulness

- `FileCapabilityPolicy` exists in `src/main/java/dev/talos/core/ingest/FileCapabilityPolicy.java`.
  - `POLICY_VERSION` is `file-capability-policy-v2` at line 12.
  - PDF/Office/image/archive/compiled/binary families are classified in the extension map.
- `UnsupportedDocumentFormats` remains as a direct read/write capability boundary in `src/main/java/dev/talos/core/ingest/UnsupportedDocumentFormats.java`.
- Runtime final-answer shaping exists in `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`.
  - `overrideUnsupportedDocumentClaimsIfNeeded(...)` starts at line 4705.
  - Unsupported search notes and unsupported document claim detection are implemented in the same section.
- `src/test/java/dev/talos/cli/modes/UnsupportedFinalAnswerTruthfulnessTest.java` covers DOCX, XLSX compare, PDF, PPTX, image, archive, binary, PDF/image compare, archive search skip note, PDF write, DOCX create, and scripted model fabrication attempts.

Verification answers:

14. Unsupported-format tests cover the major requested unsupported families: PDF, Word/DOCX, Excel/XLSX, PowerPoint/PPTX, images/scans, archives, generic binaries, compare flows, skipped search notes, and unsupported write/create claims.

## 9. Live audit and reports

- Later evidence supersedes this subsection for document extraction: the focused two-model beta-core capability audit `capability-live-audit-20260516-210854` ran against GPT-OSS and Qwen, with targeted artifact scan passing afterward. Images and PowerPoint were intentionally excluded from beta-core scope.
- The broader historical T267 32-prompt bank remains a runbook/status document, not a completed private-document evidence packet.
- Latest backend evidence: `scripts/run-t267-live-audit.ps1 -SmokeModels -StopStaleServers` produced smoke audit id `t267-live-audit-20260516-091319`, where GPT-OSS returned `GPTOSS_SMOKE_123`, Qwen returned `QWEN_SMOKE_123`, targeted artifact canary scan passed on the smoke roots, and repo-owned stale server count after the run was 0.
- Deterministic test lifecycle evidence: tests that previously loaded the real user LLM config now use placeholder/scripted LLMs, and `./gradlew.bat clean check e2eTest --no-daemon` completed with repo-owned `llama-server.exe` process count 0.
- `work-cycle-docs/reports/next-beta-readiness-hardening-report.md` states `Not release-ready`.
- `work-cycle-docs/reports/t267-and-file-format-release-gate.md` states `Not release-ready` and forbids private-document and unsupported-extraction claims.

Verification answers:

16. Superseded for the focused document-capability bank: a later two-model beta-core capability audit ran. The broader historical private-document bank remains incomplete. Images/OCR and PowerPoint are frozen for v1.
17. The inspected release reports do not mark Talos private-document release-ready. They correctly keep live audit and unsupported extraction as blockers. README also forbids tax/health/legal/family/admin private-document positioning and now states `/privacy` persistence semantics directly.

## 10. Ticket freshness

- T267-T285 open tickets exist under `work-cycle-docs/tickets/open/`.
- T281 covers private-mode UX and sensitive-folder warning and now reflects the `id` tokenization false-positive work.
- T283 remains open and is justified by remaining raw/partially raw log call sites.
- T284/T280 cover the live audit blocker.
- T286-T289 exist for:
  - two-model local backend setup,
  - sensitive-workspace detector tokenization,
  - runtime artifact scan release task,
  - private-mode scripted e2e scenarios.

Verification answers:

18. Some tickets are stale relative to the current code and this verification: T281 needs tokenization detail, T283 needs a call-site table update, T285 needs release-task coverage, and new T286-T289 tickets are required.

## 11. Implementation plan for this pass

Targeted work only:

1. Correct `/privacy` status/help/README wording to state session/current-config semantics and persistent config instructions.
2. Add tests and token-aware matching for short sensitive terms such as `id` without reading file contents.
3. Convert high-risk log call sites to `SafeLogFormatter` and update `log-redaction-audit.md` with a call-site table.
4. Add a maintainer-facing targeted runtime artifact canary scan utility/task and tests.
5. Make the live two-model audit runbook executable with a preflight script and precise BLOCKED/PARTIAL/PASS reporting. Completed for preflight/smoke; full prompt-bank execution remains open.
6. Add or extend private-mode scripted/e2e tests where practical.
7. Update README, release reports, and tickets T267-T289 without claiming private-document release readiness.
