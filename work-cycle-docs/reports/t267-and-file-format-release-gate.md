# T267 and File-Format Release Gate Report

## 1. Executive verdict

Release-ready only for developer/text-project beta, not private-document beta.

2026-05-18 superseding update: Talos now has narrow local extraction for
text-bearing PDFs, `.docx`, `.xls`, and `.xlsx`. Images and PowerPoint are frozen
out of beta and remain v1/open issues. The latest two-model private-folder bank
ran against GPT-OSS and Qwen with audit id `capability-live-audit-20260518-004603`,
and the targeted runtime artifact canary scan passed on that audit root. Private-document
beta remains blocked by broader sensitive-paperwork fixtures, approval-sensitive
transcript capture, explicit send-to-model UX/tracing, adversarial document quality
evidence, and the still-present developer/default mode risk that approved direct
protected reads may enter model context.

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

- The broader historical T267 approval-sensitive prompt bank is not fully automated. The focused beta-core/private-folder bank has run, but approval grant/deny transcripts still require a synchronized runner or human-operated capture.
- Private mode now has `/privacy` REPL UX, warning-only sensitive workspace detection, and focused live prompt-bank evidence. It still lacks large real-world private-folder fixture evidence.
- Artifact scan is CI-grade for controlled generated surfaces and targeted live-audit roots, but private-document release still requires broader artifact coverage after approval-sensitive runs.

## 4. Unsupported-format status

| Format family | Extensions | Current behavior | Tests | Verdict |
|---|---|---|---|---|
| PDF | `.pdf` | Local text extraction enabled through PDFBox; layout/visual order limitations are reported. | `DocumentExtractionAdaptersTest`, `ReadFileToolTest`, `GrepToolTest`, live audit `05-pdf-summary` | Extractable text, not layout-perfect |
| Word | `.docx`; `.doc` deferred | DOCX text extraction enabled through POI XWPF. Legacy `.doc` remains deferred/unsupported. | `DocumentExtractionAdaptersTest`, live audit `06-docx-summary` | DOCX extractable; DOC deferred |
| Excel | `.xls`, `.xlsx` | Local cell text extraction enabled through POI HSSF/XSSF; formulas are not recalculated; formula cells show formula text plus cached display value when available; large output is partial/truncated. | `DocumentExtractionAdaptersTest`, `DocumentExtractionCanonicalFixturesTest`, live audit `07-xlsx-summary`, `10-compare-xlsx-text` | Extractable cell text, not spreadsheet execution |
| PowerPoint | `.ppt`, `.pptx` | Frozen out of beta; truthful refusal remains required. | `UnsupportedFinalAnswerTruthfulnessTest`; excluded from beta-core live audit | v1/open issue |
| Images/scans | `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`, `.tif`, `.tiff` | Frozen out of beta; experimental OCR adapter exists but is not beta evidence. | `DocumentExtractionAdaptersTest`, `DocumentExtractionPreflightTest`; excluded from beta-core live audit | v1/open issue |
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
- safe for arbitrary private PDFs, Word documents, Excel workbooks, or images
- can read PowerPoint decks
- can understand images visually
- can inspect arbitrary binary files
- all protected content is guaranteed never to reach model context

## 7. Tickets created/updated

T267-T289 are open/updated for indirect-read safety, unsupported-format truthfulness, RAG policy metadata, artifact scanning, approved protected-read scope, log/parameter redaction, private-mode UX, source crosscheck discipline, artifact scanner surface coverage, live audit, model setup, detector tokenization, release artifact scan task, and private-mode scripted e2e coverage.

## 8. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommand*" --tests "*SensitiveWorkspaceDetector*" --tests "*SensitiveLog*" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaults*" --tests "*Rag*Dirty*" --tests "*UnsupportedFinalAnswer*" --tests "*ReadmePrivacy*" --no-daemon` - passed.
- `./gradlew.bat e2eTest --tests "*PrivateModeScriptedE2e*" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed after document extraction and evidence-gate fixes.
- `./gradlew.bat installDist --no-daemon` - passed.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -StopStaleServers` - passed with audit id `capability-live-audit-20260516-210854`.
- `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260516-210854,local/manual-workspaces/capability-live-audit-20260516-210854" ... --no-daemon` - passed.

## 9. Tests not run

- Image/OCR and PowerPoint were intentionally excluded from the beta-core audit because they are frozen for v1.

## 10. Remaining blockers

- Not ready for sensitive personal paperwork positioning.
- PowerPoint and legacy `.doc` remain unsupported/deferred.
- Image/OCR remains frozen for v1; do not claim beta image support.
- Private-document beta still needs broader real private-paperwork fixtures and review.
- Developer/default approved direct protected reads can still enter model context after approval; this must remain explicit in product claims.
