# T267 and File-Format Release Gate Report

## 1. Executive verdict

Not release-ready.

The deterministic hardening work improved the developer/text-project beta boundary, and the required Gradle gate passes. Private-document beta remains blocked: the two-model live prompt-bank audit has not run in this pass, private-folder UX is only a config-level V1, and Talos still has no local PDF/Office/image/archive extraction.

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

Still open:

- Two-model live audit not run in this pass.
- Private-folder mode has no `/privacy private on/off` UX yet.
- Artifact scan is CI-grade for controlled generated surfaces, but legacy ignored manual audit folders are deliberately skipped in the unit scan.

## 4. Unsupported-format status

| Format family | Extensions | Current behavior | Tests | Verdict |
|---|---|---|---|---|
| PDF | `.pdf` | Classified unsupported; no extraction. | Existing unsupported tests | Not extractable |
| Word | `.doc`, `.docx` | Classified unsupported; final-answer fabrication guarded in scripted tests. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| Excel | `.xls`, `.xlsx` | Classified unsupported; compare flow must report partial only. | `UnsupportedFinalAnswerTruthfulnessTest` | Not extractable |
| PowerPoint | `.ppt`, `.pptx` | Classified unsupported; no extraction. | Existing capability tests | Not extractable |
| Images/scans | `.png`, `.jpg`, `.jpeg`, `.gif`, `.bmp`, `.webp`, `.tif`, `.tiff` | Classified unsupported image/scan. No OCR. | Existing capability tests | Not extractable |
| Archives | `.zip`, `.tar`, `.gz`, `.tgz`, `.7z`, `.rar` | Classified unsupported archive. | Existing capability tests | Not extractable |
| Binaries | `.exe`, `.dll`, `.so`, `.dylib`, `.class`, `.jar`, `.war`, `.ear`, `.bin`, `.dat` | Classified unsupported binary/compiled. | Existing capability tests | Not extractable |
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
| logs | Tool params and command output now use central redaction helpers. | `SensitiveLogRedactionTest` | Pass for focused boundary |
| RAG index | New indexes write policy metadata; stale/missing metadata rebuilds. | `IndexerPolicyMetadataTest` | Partial until broader RAG tests run |
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

T267-T280 are open/updated for indirect-read safety, unsupported-format truthfulness, RAG policy metadata, artifact scanning, approved protected-read scope, log/parameter redaction, private-folder mode, source crosscheck discipline, and live audit.

## 8. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScopePolicyTest" --tests "*ProtectedReadScopeIntegrationTest" --tests "*SensitiveLogRedactionTest" --tests "*ArtifactCanaryScanTest" --tests "*IndexerPolicyMetadataTest" --tests "*UnsupportedFinalAnswerTruthfulnessTest" --no-daemon` - passed.
- `./gradlew.bat test --tests "dev.talos.app.ui.TerminalFirstRunTest" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed.

## 9. Tests not run

- Two-model live Talos prompt-bank audit not run in this pass.

## 10. Remaining blockers

- Not ready for sensitive personal paperwork positioning.
- Live two-model audit still required.
- Private-folder mode needs user-facing UX and broader tests.
- Local PDF/Office/image/OCR/archive extraction is not implemented.
