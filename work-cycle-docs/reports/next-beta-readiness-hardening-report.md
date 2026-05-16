# Next Beta Readiness Hardening Report

## 1. Executive verdict

Not release-ready

Deterministic hardening improved protected-read scope control, private-mode UX, sensitive-folder warnings, log/parameter redaction, artifact scanning, RAG index metadata, dirty-index coverage, and unsupported-format answer correction. The release gate is still not clean because the two-model live audit has not run and document extraction is still unsupported.

Final pre-beta update: `/privacy` persistence wording is now explicit, sensitive-folder `id` matching is token-aware, high-risk raw log exception-message call sites were converted to safe formatting, a targeted release artifact scan task exists, and the live audit preflight is reproducible. 2026-05-16 update: both Qwen and GPT-OSS local GGUF files were found and both passed a minimal Talos smoke prompt after stale repo-owned `llama-server.exe` processes were stopped. The live prompt-bank audit itself still has not run.

## 2. What changed in this pass

- Added `ProtectedReadScopePolicy`.
- Added private-mode `LOCAL_DISPLAY_ONLY` default for approved protected reads.
- Updated tool-result model handoff so private/local-display-only protected reads do not send raw protected content back to the model.
- Added central tool-parameter/log sanitization helpers.
- Routed command output redaction through `ProtectedContentPolicy`.
- Added `ArtifactCanaryScanner`.
- Added RAG index privacy/file-capability metadata and stale-index rebuild behavior.
- Added focused tests for scope, logs, artifact scanning, RAG metadata, and unsupported final-answer truthfulness.
- Added `/privacy status`, `/privacy private on`, `/privacy private off`, and `/privacy help`.
- Added warning-only sensitive workspace detection.
- Clarified `/privacy` as current session/config state only; persistent defaults require editing `~/.talos/config.yaml`.
- Tokenized short sensitive-folder terms such as `id` to avoid `valid-project`/`grid-ui` false positives.
- Added `ArtifactCanaryScanCli` and Gradle task `checkRuntimeArtifactCanaries` for live-audit artifact directories.
- Updated `scripts/run-t267-live-audit.ps1` preflight to check actual managed `llama.cpp` server/model files and the required sequential isolated-config strategy.
- Added initial private-mode scripted e2e tests.
- Added Lucene-backed dirty-index integration tests for missing metadata, config-hash changes, old protected chunks, and private-mode retrieval disablement.
- Updated README, source crosscheck, source matrix, release-gate report, live-audit runbook, and tickets.

## 3. Approved protected read scope status

| Mode | Local display | Sent to model? | Persisted raw? | Tests | Verdict |
|---|---|---|---|---|---|
| developer/default | Approved direct read allowed | Yes, current default preserves existing behavior | No raw persistence by default | `ProtectedReadScopePolicyTest` | Explicit risk |
| private mode | Approved direct read allowed as local display only | No by default | No raw persistence by default | `ProtectedReadScopeIntegrationTest` | Partial pass |
| explicit send-to-model | Requires configured `SEND_TO_MODEL_CONTEXT` and private-mode opt-in | Yes | No raw persistence by default | `ProtectedReadScopePolicyTest` | Explicit risk |
| denied read | Not displayed | No | No | Existing protected-read denial tests | Pass for tested path |

## 4. Log/parameter redaction status

| Surface | Raw sensitive args possible? | Evidence | Verdict |
|---|---|---|---|
| debug logs | Reduced; formatted tool params use sanitizer | `SensitiveLogRedactionTest` | Focused pass |
| tool call params | Sanitized in execution-stage debug formatting | `ProtectedContentPolicy.sanitizeToolParameters` | Focused pass |
| command args | Approval detail sanitized; command-plan/log paths need live failure-path capture | code review | Partial |
| command stdout/stderr | Central redaction now used | `ProcessCommandRunner` | Focused pass |
| provider-body captures | Existing prompt-debug redaction path plus indirect tool-result sanitizer | existing tests | Partial |
| approval prompt logs | Local approval prompts intentionally show the target path for user control; persisted approval/log artifacts still need broader redaction review | code review | Partial |
| exception messages | High-risk raw `LOG.* getMessage()` / `e.toString()` call sites converted or source-guarded | `SensitiveLogRedactionTest` | Focused pass |
| RAG trace | Snippet text and trace/failure summaries sanitized in touched paths | code review + tests | Focused pass |
| session/turn log | Persistence logs now safe-format paths/session ids/exception messages; persistence content remains redacted | existing tests + source audit | Focused pass |

## 5. RAG dirty index status

New indexes write `talos-index-metadata.json` with schema, privacy policy version, file-capability policy version, RAG config hash, workspace root hash, creation time, and Talos version. `RagService` treats valid Lucene indexes with missing/stale metadata as dirty and rebuilds them before retrieval.

Focused Lucene-backed integration now covers missing metadata, old protected chunks, config-hash changes, and private-mode retrieval disablement. Remaining risk: live prompt-bank coverage has not exercised this with local models.

## 6. Unsupported-format final-answer status

Scripted model tests now cover fabricated summaries/claims across PDF, Word/DOCX, Excel/XLSX, PowerPoint/PPTX, images, archives, binaries, compare flows, skipped archive search, and unsupported PDF/DOCX write attempts. Runtime answer shaping removes unsupported-family claims and prepends a document capability note.

Remaining risk: live model behavior still needs the prompt-bank audit.

## 7. Private-folder mode status

Minimal user-visible V1 exists:

- `privacy.mode = private`
- private mode disables RAG retrieval/indexing by default
- approved protected reads default to local-display-only model handoff
- `/privacy status`
- `/privacy private on`
- `/privacy private off`
- `/privacy help`
- warning-only sensitive workspace detection

Missing:

- broader e2e private-mode scenarios beyond the initial scripted read/grep cases
- two-model live audit evidence

## 8. Artifact canary scan status

Automated: yes, as JUnit test coverage through `ArtifactCanaryScanTest`.

Release-facing targeted task: yes.

Command:

- `./gradlew.bat test --tests "*ArtifactCanary*" --no-daemon`
- `./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<audit-id>,local/manual-workspaces/<audit-id>" --no-daemon`
- `./gradlew.bat clean check e2eTest --no-daemon`

Directories scanned in focused current-artifact test:

- `build`
- `local`

Allowlist/skip behavior:

- explicit allowlist paths are supported
- compiled/generated test infrastructure and ignored legacy manual audit workspaces are skipped to avoid false positives from source fixtures or historical manual runs

Result:

- focused artifact scanner tests passed.
- targeted task exists and is intended for completed live-audit directories.

## 9. Two-model live audit status

PARTIAL / prompt bank not run.

Models/backend: prompt bank not started. Prior `ollama list` crashed with access violation `0xc0000005`. Updated preflight now finds the managed `llama.cpp` server, GPT-OSS GGUF, and Qwen GGUF. A Qwen startup attempt initially failed because stale repo-owned `llama-server.exe` processes left only 282 MiB free GPU memory; after stopping 53 stale repo-owned servers, both Qwen and GPT-OSS answered model-forced smoke prompts through isolated temp-home configs.

Artifacts: executable runbook and preflight helper exist in `work-cycle-docs/reports/t267-live-two-model-audit.md` and `scripts/run-t267-live-audit.ps1`.

Verdict: release blocker remains because the smoke prompts do not replace the full prompt-bank audit.

## 10. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommand*" --tests "*SensitiveWorkspaceDetector*" --tests "*SensitiveLog*" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaults*" --tests "*Rag*Dirty*" --tests "*UnsupportedFinalAnswer*" --tests "*ReadmePrivacy*" --no-daemon` - passed.
- `./gradlew.bat e2eTest --tests "*PrivateModeScriptedE2e*" --no-daemon` - passed.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -PreflightOnly` - passed after updated file-based preflight and stale `llama-server.exe` cleanup; prompt bank not run.
- Isolated model smoke prompts through Talos passed for Qwen (`QWEN_SMOKE_123`) and GPT-OSS (`GPTOSS_SMOKE_123`).
- `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t267-live-audit-20260516-074959,local/manual-workspaces/t267-live-audit-20260516-074959" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed.

## 11. Tests not run

- Live two-model prompt-bank audit not run. The local model/backend pair is now smoke-verified, but the approval-sensitive prompt bank still needs synchronized execution and evidence capture.
- Full private-mode live prompt-bank not run; initial scripted private-mode e2e tests were added.

## 12. Remaining blockers

- Two-model live audit with Qwen and GPT-OSS.
- Broader private-mode live prompt-bank evidence.
- Targeted artifact scan on completed live-audit artifacts.
- Local PDF/Office/image/OCR/archive extraction if private-document positioning is desired.

## 13. Allowed product claims

- local developer workspace assistant
- code/text/config/static-web assistant
- approved edits with traces/evidence
- non-sensitive workspace folders
- unsupported formats are identified honestly

## 14. Forbidden product claims

- safe for tax folders
- safe for health records
- safe for legal paperwork
- safe for family/admin document folders
- can read PDFs
- can read Word documents
- can read Excel workbooks
- can read PowerPoint decks
- can summarize images/scans
- can inspect arbitrary binary files
- guarantees no protected content reaches model context
