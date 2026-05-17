# Next Beta Readiness Hardening Report

## 1. Executive verdict

Release-ready only for developer/text-project beta, not private-document beta.

2026-05-18 superseding update: PDF text extraction, DOCX text extraction,
XLS/XLSX cell extraction, and extraction-aware grep/RAG plumbing are implemented
behind runtime policy. Images and PowerPoint are frozen out of beta. A two-model
private-folder bank audit ran against GPT-OSS and Qwen with audit id
`capability-live-audit-20260518-004603`, and the targeted runtime artifact
canary scan passed. Private-document beta remains blocked by broader
sensitive-paperwork fixtures, approval-sensitive transcript capture,
per-turn send-to-model UX/tracing, adversarial document quality evidence, and
the explicit developer/default-mode risk that approved direct protected reads
may enter model context.

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
- Extended `scripts/run-t267-live-audit.ps1` with `-StopStaleServers` and `-SmokeModels` so maintainers can clean repo-owned stale managed backends and prove both audit models answer through isolated Talos configs before attempting the prompt bank.
- Added initial private-mode scripted e2e tests.
- Added Lucene-backed dirty-index integration tests for missing metadata, config-hash changes, old protected chunks, and private-mode retrieval disablement.
- Added a central document extraction service with PDFBox PDF extraction, POI DOCX/XLS/XLSX extraction, and a bounded local OCR command adapter.
- Routed `read_file`, native grep, slash grep, and RAG indexing through extraction-aware capability policy.
- Added config-aware evidence gating so enabled extractable documents are read before answers and disabled/deferred formats still trigger truthfulness constraints.
- Added a capability live-audit script and ran a two-model audit against GPT-OSS and Qwen.
- Corrected unit tests that accidentally loaded the real user LLM config (`AskModeTest`, `RagModeToolLoopTest`, `ToolCallLoopP0Test`, and `ConversationCompactionTest`) so deterministic tests use placeholder/scripted LLMs and do not launch managed `llama.cpp`.
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

Focused Lucene-backed integration now covers missing metadata, old protected chunks, config-hash changes, and private-mode retrieval disablement. Remaining risk: larger private-folder corpora and approval-sensitive transcripts have not exercised this with local models.

## 6. Unsupported-format final-answer status

Scripted model tests now cover fabricated summaries/claims across PDF, Word/DOCX, Excel/XLSX, PowerPoint/PPTX, images, archives, binaries, compare flows, skipped archive search, and unsupported PDF/DOCX write attempts. Runtime answer shaping removes unsupported-family claims and prepends a document capability note.

Remaining risk: broader live model behavior still needs larger private-document and approval-sensitive prompt-bank coverage.

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

- larger real-world private-mode scenarios beyond generated fixtures
- approval-sensitive transcript evidence

## 8. Artifact canary scan status

Automated: yes, as JUnit test coverage through `ArtifactCanaryScanTest`.

Release-facing targeted task: yes.
It requires explicit `-PartifactScanRoots=...`; no-root invocation fails fast so old ignored manual-audit directories are not scanned accidentally.

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
- targeted task passed on `capability-live-audit-20260518-004603`.

## 9. Two-model live audit status

PASS for the focused capability and scripted private-folder prompt banks, still not private-document release-ready.

Models/backend: managed `llama.cpp` with GPT-OSS and Qwen ran sequentially through isolated temp-home configs. The latest private-folder bank audit is `capability-live-audit-20260518-004603`.

Artifacts: `local/manual-testing/capability-live-audit-20260518-004603/LIVE-CAPABILITY-AUDIT-RESULTS.md`, `LIVE-CAPABILITY-AUDIT-SUMMARY.csv`, and `PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md`; runtime workspaces under `local/manual-workspaces/capability-live-audit-20260518-004603`.

Format scope: beta core. Image/OCR and PowerPoint prompts were intentionally excluded.

Verdict: the focused two-model capability/private-folder bank passed its process/tool-artifact heuristics, but it is not a substitute for broader private-document correctness/quality evaluation or approval-sensitive transcript evidence.

## 10. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*PrivacyCommand*" --tests "*SensitiveWorkspaceDetector*" --tests "*SensitiveLog*" --tests "*ArtifactCanary*" --tests "*ConfigPrivacyDefaults*" --tests "*Rag*Dirty*" --tests "*UnsupportedFinalAnswer*" --tests "*ReadmePrivacy*" --no-daemon` - passed.
- `./gradlew.bat e2eTest --tests "*PrivateModeScriptedE2e*" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed after document extraction/evidence-gate fixes.
- `./gradlew.bat installDist --no-daemon` - passed.
- `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank -StopStaleServers` - passed with audit id `capability-live-audit-20260518-004603`.
- `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260518-004603,local/manual-workspaces/capability-live-audit-20260518-004603" ... --no-daemon` - passed.

## 11. Tests not run

- Image/OCR and PowerPoint were intentionally excluded from beta-core scope.
- Full tax/health/legal/admin paperwork corpus audit not run.
- Approval-sensitive live transcript not automated yet.

## 12. Remaining blockers

- Broader private-mode and private-paperwork corpus evidence.
- Synchronized or human-operated approval-sensitive transcript capture.
- PowerPoint and legacy `.doc` remain unsupported/deferred.
- Image/OCR remains frozen for v1.
- Developer/default approved direct protected reads can still enter model context after approval.

## 13. Allowed product claims

- local developer workspace assistant
- code/text/config/static-web assistant
- approved edits with traces/evidence
- non-sensitive workspace folders
- PDF text extraction with layout/order limitations
- DOCX text extraction with structure/layout limitations
- XLS/XLSX visible cell extraction without formula recalculation; formula cells expose formula text plus cached display value when available, and large output can be partial/truncated
- unsupported/deferred formats are identified honestly

## 14. Forbidden product claims

- safe for tax folders
- safe for health records
- safe for legal paperwork
- safe for family/admin document folders
- safe for arbitrary private PDFs, Word documents, Excel workbooks, or images
- can read PowerPoint decks
- image OCR, image understanding, or scan understanding
- can inspect arbitrary binary files
- guarantees no protected content reaches model context
