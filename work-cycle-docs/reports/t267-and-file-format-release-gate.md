# T267 and File-Format Release Gate Report

## 1. Executive verdict

Not release-ready.

The deterministic hardening work improved the developer/text-project beta boundary. Private-document beta remains blocked: the two-model live prompt-bank audit has not run in this pass, private mode still needs broader live coverage, and Talos still has no local PDF/Office/image/archive extraction. Final pre-beta updates added exact `/privacy` persistence wording, tokenized sensitive-folder warnings, stronger log call-site redaction, a targeted live-audit artifact scan task, and an executable live-audit preflight. 2026-05-16 update: both Qwen and GPT-OSS local GGUF files were found and both passed minimal Talos smoke prompts after stale repo-owned `llama-server.exe` processes were stopped; the full prompt bank remains unrun.

## 2. Source crosscheck summary

OpenAI Codex docs separate sandbox mode from approval policy: the sandbox is the technical boundary, while approval controls when the agent must pause. Gemini CLI docs likewise show that tools read files, execute commands, and require confirmation/sandbox policy for risky actions. Both support the Talos rule that approval is not privacy safety and model prompts are not the security boundary.

The project-provided `alex000kim-article.txt` source was searched again and is absent from this workspace. No claims in this report rely on it.

## 3. T267 status

Status: partial.

Fixed in this pass:

- Added config-backed protected-read scope policy.
- Private mode defaults approved protected reads to `LOCAL_DISPLAY_ONLY`.
- Default/developer mode preserves existing approved direct-read behavior unless config changes.
- Tool-call parameter/debug formatting now delegates to protected-content sanitization.
- Command stdout/stderr redaction delegates to the central policy.
- Artifact canary scanner exists as a JUnit path and runs under `test`/`check`.
- New RAG indexes write privacy/file-capability policy metadata.
- Stale or missing-policy RAG metadata causes rebuild before retrieval.
- Unsupported-format final-answer correction is covered for scripted summarize/compare fabrication cases.
- Bounded Ollama probe subprocesses prevent `TerminalFirstRunTest` from hanging the unit-test gate.
- `/privacy` status/help now states changes are current session/config state only and do not write `~/.talos/config.yaml`.
- Sensitive workspace detection no longer treats `id` as an arbitrary substring in ordinary names.
- High-risk raw exception-message log call sites now use `SafeLogFormatter` and are source-guarded by tests.
- `checkRuntimeArtifactCanaries` provides a targeted scan command for live-audit artifacts.
- `scripts/run-t267-live-audit.ps1` provides a reproducible PASS/BLOCKED model/backend preflight based on actual managed `llama.cpp` server/model files and the sequential isolated-config strategy.
- Initial private-mode scripted e2e tests cover approved local-display-only `.env` reads and grep canary omission.

Still open:

- Two-model live prompt-bank audit not run in this pass. Updated preflight and smoke checks show both model files are present and both models can answer through Talos after stale repo-owned `llama-server.exe` processes are stopped.
- Private mode now has a minimal `/privacy` REPL UX and warning-only sensitive workspace detection, but it still lacks live prompt-bank evidence.
- Artifact scan is CI-grade for controlled generated surfaces and now has a targeted live-audit scan task, but that task has not been run on completed live-audit artifacts because the live audit has not run.

## 4. Unsupported-format status

| Format family | Extensions | Current behavior | Tests | Verdict |
|---|---|---|---|---|
| PDF | `.pdf` | Classified unsupported; no extraction; scripted fabrication override covered. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Word | `.doc`, `.docx` | Classified unsupported; final-answer fabrication guarded in scripted tests. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Excel | `.xls`, `.xlsx` | Classified unsupported; compare flow must report partial only. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| PowerPoint | `.ppt`, `.pptx` | Classified unsupported; scripted fabrication override covered. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Images/scans | `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`, `.tif`, `.tiff` | Classified unsupported image/scan. No OCR; scripted fabrication override covered. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Archives | `.zip`, `.tar`, `.gz`, `.tgz`, `.7z`, `.rar` | Classified unsupported archive; search must disclose skipped archives. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Binaries | `.exe`, `.dll`, `.so`, `.dylib`, `.class`, `.jar`, `.war`, `.ear`, `.bin`, `.dat` | Classified unsupported binary/compiled; scripted fabrication override covered. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Unknown text-like files | no known unsupported extension, no binary sniff failure | Text attempt allowed. | Existing parser/read/search tests | Supported cautiously |

## 5. Artifact safety status

| Surface | Can raw protected/canary content appear? | Evidence | Verdict |
|---|---|---|---|
| model context | Indirect reads should not; default approved direct protected reads may in developer mode. | `ToolCallExecutionStage`, `ProtectedReadScopeIntegrationTest` | Partial |
| provider body | Indirect read path covered by sanitizer; approved direct send-to-model scope remains explicit risk. | Prompt-debug/provider-body redaction tests plus new scope policy | Partial |
| prompt-debug markdown | Redacted by default for tested surfaces. | Existing prompt-debug tests | Pass for tested boundary |
| prompt-debug provider-body JSON | Redacted by default for tested surfaces. | Existing prompt-debug tests | Pass for tested boundary |
| local turn trace | Central policy covers canaries/private markers. | Existing trace tests | Pass for tested boundary |
| session JSON | Redacted through session persistence path. | Existing session tests | Pass for tested boundary |
| turn JSONL | Redacted for tested turn records. | Existing turn-log tests | Pass for tested boundary |
| logs | Tool params, command output, high-risk exception-message logs, session/turn persistence logs, and provider parse logs use central safe formatting in tested/source-scanned paths. | `SensitiveLogRedactionTest`, `log-redaction-audit.md` | Focused pass |
| RAG index | New indexes write policy metadata; stale/missing metadata rebuilds; dirty-index integration covers old protected chunks. | `IndexerPolicyMetadataTest`, `RagDirtyIndexIntegrationTest` | Focused pass |
| final answer | Unsupported summarize/compare fabrication guarded in focused tests. | `UnsupportedFinalAnswerTruthfulnessTest` | Partial until live audit |

## 6. User-facing copy recommendation

Allowed claims:

- local developer workspace assistant
- good for code, text, config, CSV/TSV, and static web folders
- approved edits and evidence-oriented outcomes
- local-first execution harness
- unsupported documents are identified honestly rather than silently summarized

Forbidden claims unless all private-document gates pass:

- safe for tax folders
- safe for health documents
- safe for legal paperwork
- safe for family/admin private paperwork
- can read PDFs
- can read Word documents
- can read Excel workbooks
- can read PowerPoint decks
- can summarize images/scans
- can inspect arbitrary binary files
- all protected content is guaranteed never to reach model context

## 7. Tickets created/updated

T267-T289 are open/updated for indirect-read safety, unsupported-format truthfulness, RAG policy metadata, artifact scanning, approved protected-read scope, log/parameter redaction, private-mode UX, source crosscheck discipline, artifact scanner surface coverage, live audit, model setup, detector tokenization, release artifact scan task, and private-mode scripted e2e coverage.

## 8. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommand*" --tests "*SensitiveWorkspaceDetector*" --tests "*SensitiveLog*" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaults*" --tests "*Rag*Dirty*" --tests "*UnsupportedFinalAnswer*" --tests "*ReadmePrivacy*" --no-daemon` - passed.
- `./gradlew.bat e2eTest --tests "*PrivateModeScriptedE2e*" --no-daemon` - passed.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -PreflightOnly` - passed after updated file-based preflight and stale server cleanup; prompt bank not run.
- Isolated Talos model smoke prompts passed for Qwen (`QWEN_SMOKE_123`) and GPT-OSS (`GPTOSS_SMOKE_123`).
- `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t267-live-audit-20260516-091319,local/manual-workspaces/t267-live-audit-20260516-091319" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed; repo-owned `llama-server.exe` process count after the run was 0.

## 9. Tests not run

- Two-model live Talos prompt-bank audit not run in this pass. Current preflight/smoke evidence proves both local model files and managed `llama.cpp` can run, but the approval-sensitive prompt bank still needs synchronized execution and classification.

## 10. Remaining blockers

- Not ready for sensitive personal paperwork positioning.
- Live two-model audit still required.
- Private mode needs broader live prompt-bank evidence.
- Targeted runtime artifact scan must be run against completed live-audit artifacts.
- Local PDF/Office/image/OCR/archive extraction is not implemented.
