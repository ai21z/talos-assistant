# Next Beta Readiness Hardening Report

## 1. Executive verdict

Not release-ready

Deterministic hardening improved protected-read scope control, private-mode UX, sensitive-folder warnings, log/parameter redaction, artifact scanning, RAG index metadata, dirty-index coverage, and unsupported-format answer correction. The release gate is still not clean because the two-model live audit has not run and document extraction is still unsupported.

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
| command args | Approval detail sanitized; broader command-plan logs still need review | code review | Partial |
| command stdout/stderr | Central redaction now used | `ProcessCommandRunner` | Focused pass |
| provider-body captures | Existing prompt-debug redaction path plus indirect tool-result sanitizer | existing tests | Partial |
| approval prompt logs | Local approval prompts intentionally show the target path for user control; persisted approval/log artifacts still need broader redaction review | code review | Partial |
| exception messages | Tool exception warning sanitized | code review | Partial |
| RAG trace | Snippet text sanitized; trace summaries still need broader artifact tests | code review | Partial |
| session/turn log | Existing persistence redaction tests remain relevant | existing tests | Partial |

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

- broad e2e private-mode scenarios
- two-model live audit evidence

## 8. Artifact canary scan status

Automated: yes, as JUnit test coverage through `ArtifactCanaryScanTest`.

Command:

- `./gradlew.bat test --tests "*ArtifactCanary*" --no-daemon`
- `./gradlew.bat clean check e2eTest --no-daemon`

Directories scanned in focused current-artifact test:

- `build`
- `local`

Allowlist/skip behavior:

- explicit allowlist paths are supported
- compiled/generated test infrastructure and ignored legacy manual audit workspaces are skipped to avoid false positives from source fixtures or historical manual runs

Result:

- focused artifact scanner tests passed.
- full `clean check e2eTest` passed on 2026-05-15, so the scanner currently runs through `check`.

## 9. Two-model live audit status

Not run.

Models/backend: not started. `ollama list` crashed with access violation `0xc0000005`; local Talos config showed a GPT-OSS llama.cpp profile but no Qwen profile for the required pair.

Artifacts: runbook created in `work-cycle-docs/reports/t267-live-two-model-audit.md`.

Verdict: release blocker remains.

## 10. Tests run

- `./gradlew.bat test --tests "*ProtectedReadScope*" --tests "*Privacy*" --tests "*SensitiveFolder*" --tests "*SensitiveWorkspaceDetector*" --tests "*SensitiveLog*" --tests "*ArtifactCanary*" --tests "*IndexerPolicyMetadata*" --tests "*Rag*Dirty*" --tests "*UnsupportedFinalAnswer*" --tests "*Config*Privacy*" --no-daemon` - passed.
- `./gradlew.bat clean check e2eTest --no-daemon` - passed.

## 11. Tests not run

- Live two-model prompt-bank audit not run because the required local model/backend pair was unavailable. `ollama list` crashed with access violation `0xc0000005`; local Talos config showed GPT-OSS only, not the required Qwen/GPT-OSS pair.
- Focused private-mode e2e scenario pack not added yet; unit/integration coverage exists.

## 12. Remaining blockers

- Two-model live audit with Qwen and GPT-OSS.
- Broader private-mode live/e2e prompt-bank evidence.
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
