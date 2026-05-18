# T306 - Synchronized Approval Live Audit Runner

Status: open
Severity: high / P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-18
Owner: unassigned

## Problem

The current live-audit script intentionally avoids approval-sensitive prompts because piped stdin can desynchronize approval responses and later slash commands. That protects audit integrity, but it leaves approval grant/deny behavior as a manual transcript requirement.

## Evidence from current code

- `RunCmd` and `TalosBootstrap` route scripted stdin and approval prompts through a shared input owner.
- `scripts/run-capability-live-audit.ps1` now generates `PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md` for approval-sensitive probes instead of pretending they are automated.
- Private-folder bank audit `capability-live-audit-20260518-004603` passed non-interactive private-folder probes, but did not automate approval grant/deny prompts.
- `SynchronizedApprovalAuditRunner` and `ScriptedApprovalGate` now provide a deterministic Java harness seam where approval prompts must be expected, matched, recorded, and answered.
- The harness can now write a reviewable artifact bundle with final answer, approval transcript, model transcript, trace JSON/text, prompt-debug/provider-body files, real `JsonSessionStore` session snapshot/turn JSONL output, workspace status, and workspace diff placeholder.
- Gradle task `runSynchronizedApprovalAudit` now runs the scripted approval bank by default and supports live mode with `-PapprovalAuditMode=live`.
- Live mode now labels summaries as `Mode: LIVE`, records the active model, and writes real prompt-debug/provider-body capture files when the provider capture path supplies them.
- `SynchronizedCliProcessDriver` and `SynchronizedCliApprovalSmokeMain` now provide a production-process smoke path that launches installed `talos run`, waits for stdout markers, and sends approval input only after the actual prompt appears.
- Gradle task `runSynchronizedApprovalCliSmoke` runs that production-process smoke after `installDist`.
- The synchronized approval bank now includes explicit private-mode protected-read `SEND_TO_MODEL_CONTEXT` opt-in.
- Audit bundle persistence now redacts explicit send-to-model protected-read answers/model transcripts/session artifacts when raw artifact persistence is disabled.
- Audit bundle writing now clears the scenario artifact directory before writing so stale files from previous runs cannot hide inside a passing audit root.

## Evidence from tests/audits

- Scripted private-folder bank: `capability-live-audit-20260518-004603`.
- The generated manual runbook lists protected-read denial, approved local-display read, explicit send-to-model opt-in, trace, prompt-debug, provider-body, session, turn JSONL, log, and artifact-scan capture requirements.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed after adding the first synchronized approval harness slice.
- The same focused e2e class now verifies that the artifact bundle is written, includes session snapshot and turn JSONL files, does not contain the raw protected test canary, and passes `ArtifactCanaryScanner.scanRuntimeArtifacts(...)`.
- `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed and wrote `build/synchronized-approval-audit/artifacts/SYNCHRONIZED-APPROVAL-AUDIT.md`.
- Two-model live synchronized approval slice ran on 2026-05-18:
  - GPT-OSS: `local/manual-testing/synchronized-approval-live-gptoss-20260518-0757/SYNCHRONIZED-APPROVAL-AUDIT.md`.
  - Qwen: `local/manual-testing/synchronized-approval-live-qwen-20260518-0810/SYNCHRONIZED-APPROVAL-AUDIT.md`.
  - Targeted scan passed:
    `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/synchronized-approval-live-gptoss-20260518-0757,local/manual-testing/synchronized-approval-live-qwen-20260518-0810" --no-daemon`.
  - Both runs captured one expected approval prompt for protected-read denial, one expected approval prompt for developer/default approved protected-read risk, and one expected approval prompt for private-mode approved local-display read.
  - Developer/default mode repeated a harmless non-canary marker from `.env` after approval. The approval transcript recorded `SEND_TO_MODEL_CONTEXT`, proving the expected explicit-risk behavior.
  - Qwen triggered runtime repair after a generic refusal; trace recorded `PROTECTED_READ_POSTCONDITION_CHECKED` with `status=REPAIRED`.
- Two-model production-process CLI smoke ran on 2026-05-18:
  - GPT-OSS: `local/manual-testing/synchronized-cli-approval-smoke-gptoss-20260518/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`.
  - Qwen: `local/manual-testing/synchronized-cli-approval-smoke-qwen-20260518/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`.
  - Both smokes observed the production CLI approval prompt, sent denial only after the prompt appeared, captured approval-blocked output, exited cleanly, and passed targeted artifact canary scans.
  - This is redirected-stdin process evidence, not true PTY/JLine rendering evidence.
- Expanded two-model live synchronized approval slice ran on 2026-05-18:
  - GPT-OSS: `local/manual-testing/synchronized-approval-live-gptoss-20260518-4case/SYNCHRONIZED-APPROVAL-AUDIT.md`.
  - Qwen: `local/manual-testing/synchronized-approval-live-qwen-20260518-4case/SYNCHRONIZED-APPROVAL-AUDIT.md`.
  - Both runs captured protected-read denial, developer/default approved protected-read risk, private-mode approved local-display read, and private-mode approved explicit send-to-model opt-in.
  - Explicit send-to-model runs recorded `SEND_TO_MODEL_CONTEXT` in approval transcripts and proved model handoff in memory, while persisted artifact files redacted the protected answer because raw artifact persistence was disabled.
  - Targeted artifact canary scan passed:
    `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/synchronized-approval-live-gptoss-20260518-4case,local/manual-testing/synchronized-approval-live-qwen-20260518-4case" --no-daemon`.
  - Direct raw-string sweep over the expanded live roots found no generated approval canaries, private-document fact canaries, developer-risk marker, or explicit opt-in marker.
- Fresh verification after the live-slice implementation:
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "*SynchronizedApproval*" --no-daemon` passed.
  - `./gradlew.bat e2eTest --tests "*SynchronizedCli*" --no-daemon` passed.
  - `./gradlew.bat test --tests "*Approval*" --no-daemon` passed.
  - `./gradlew.bat clean check e2eTest --no-daemon` passed.
  - Scripted `runSynchronizedApprovalAudit` passed after the report-label fix.
  - Scripted `runSynchronizedApprovalAudit` passed after adding the explicit send-to-model scenario and stale artifact cleanup.
  - GPT-OSS and Qwen `runSynchronizedApprovalCliSmoke` passed.
  - Targeted runtime artifact scans passed over build reports/results, docs/tickets, scripted synchronized-approval artifacts, both original live synchronized-approval roots, both expanded four-case live synchronized-approval roots, and both production-process CLI smoke roots.
  - `git diff --check` reported only a `build.gradle.kts` CRLF warning.

## User impact

Without synchronized approval capture, maintainers cannot fully reproduce the private-document release gate from one command. They must manually run approval-sensitive prompts and collect evidence carefully.

## Product risk

High. Approval behavior is a core Talos trust boundary. Private-document beta should not rely on unstructured human notes for approval grant/deny evidence.

## Runtime boundary affected

Approval prompts, protected direct reads, extracted-document send-to-model opt-in, prompt-debug, provider bodies, traces, sessions, turn JSONL, logs, and artifact scans.

## Non-goals

- No arbitrary shell automation.
- No bypassing approval policy.
- No fake "approved" state in live audit results.

## Required behavior

- A synchronized runner must be able to send user prompts and approval responses without stdin drift.
- It must capture approval prompt text, response, final answer, `/last trace`, prompt-debug save, provider body, session/turn artifacts, logs, workspace diff, and artifact scan result.
- It must distinguish approval denied, approval granted local-display-only, and explicit send-to-model opt-in cases.
- It must fail closed if the expected approval prompt does not appear.

## Proposed implementation

Add both layers:

1. a deterministic Java runtime harness that exposes approval prompt/response evidence without weakening production approval behavior, and
2. a pseudo-terminal based PowerShell/Java smoke harness that can wait for real CLI approval prompts and respond deliberately.

Keep the existing `-PrivateFolderBank` scripted path for non-interactive probes. Use the synchronized runner only for approval-sensitive cases.

## Tests

- approval_runner_denies_protected_read_and_captures_trace - initial deterministic e2e coverage added
- approval_runner_grants_local_display_read_without_model_handoff - initial deterministic e2e coverage added
- approval_runner_fails_if_approval_prompt_missing - initial deterministic e2e coverage added
- approval_runner_writes_reviewable_artifact_bundle_without_raw_protected_value - initial deterministic e2e coverage added
- approval_runner_artifact_scan_passes_on_generated_bundle - folded into the artifact bundle test
- approval_runner_summary_labels_scripted_mode - covered by the deterministic entrypoint summary test
- cli_process_driver_sends_each_line_after_expected_prompt - added
- cli_process_driver_timeout_includes_transcript_context - added
- cli_process_driver_stopped_process_fails_closed - added
- cli_smoke_summary_redacts_raw_canary_and_records_status - added
- approval_runner_explicit_send_to_model_records_scope - added
- artifact_bundle_redacts_explicit_send_to_model_protected_answer_when_raw_persistence_disabled - added
- artifact_bundle_replaces_stale_files_from_prior_run - added
- approval_runner_artifact_scan_fails_on_raw_private_fact

## Acceptance criteria

- Approval-sensitive private-folder prompts can run from a reproducible command.
- The resulting artifact directory includes all required evidence files.
- Targeted artifact scan passes.
- No private-document release claim is made until this runner or an equivalent human-operated transcript package exists and passes.

## Progress

- Deterministic Java approval harness seam exists.
- Unexpected approval prompts fail closed.
- Expected approval prompts record description, detail, synthetic prompt text, and response.
- Protected-read denial and private-mode protected-read approval are covered at the executor/runtime boundary.
- Private-mode explicit protected-read send-to-model opt-in is covered at the executor/runtime boundary.
- The harness writes a first artifact bundle: final answer, approvals JSONL, model transcript, trace JSON/text, prompt-debug/provider-body placeholder files, session snapshot, turn JSONL, workspace status, workspace diff placeholder, and summary index.
- The harness redacts persisted protected-read answers/model transcripts/session artifacts for explicit send-to-model runs when raw artifact persistence is disabled.
- The harness clears stale scenario artifact roots before writing fresh bundles.
- The generated deterministic bundle is scanned with the runtime artifact canary scanner in e2e coverage.
- A maintainer can run the deterministic bank with `./gradlew.bat runSynchronizedApprovalAudit --no-daemon`, optionally setting `-PapprovalAuditArtifactsRoot=...` and `-PapprovalAuditWorkspacesRoot=...`.
- A maintainer can run the live bank with `-PapprovalAuditMode=live`, `-PapprovalAuditConfig=...`, `-PapprovalAuditArtifactsRoot=...`, and `-PapprovalAuditWorkspacesRoot=...`.
- The GPT-OSS live slice passed for protected-read denial and private-mode approved local-display read.
- The GPT-OSS live slice passed for developer/default approved protected-read explicit risk.
- The Qwen live slice passed for protected-read denial and private-mode approved local-display read; the private-mode answer required runtime repair after model refusal.
- The Qwen live slice passed for developer/default approved protected-read explicit risk.
- The GPT-OSS expanded four-case live slice passed for explicit protected-read send-to-model opt-in with persisted artifact redaction.
- The Qwen expanded four-case live slice passed for explicit protected-read send-to-model opt-in with persisted artifact redaction.
- Live summaries now distinguish `SCRIPTED` from `LIVE` runs and include the model string.
- A maintainer can run the production-process CLI smoke with `./gradlew.bat runSynchronizedApprovalCliSmoke --no-daemon`, optionally setting `-PcliSmokeConfig=...`, `-PcliSmokeArtifactsRoot=...`, and `-PcliSmokeWorkspace=...`.
- The GPT-OSS production-process CLI smoke passed for protected-read denial prompt rendering/consumption in redirected stdin mode.
- The Qwen production-process CLI smoke passed for protected-read denial prompt rendering/consumption in redirected stdin mode.

## Remaining blockers

- Add true pseudo-terminal/JLine smoke coverage for fully interactive terminal rendering. The current CLI smoke covers synchronized redirected stdin/stdout, which is valuable but not a true terminal.
- Expand the live bank beyond protected-read cases:
  - extracted-document send-to-model disabled/enabled,
  - mutation approval denied,
  - mutation approval granted with checkpoint and verification,
  - remember approval eligibility.
- Define exact transcript schema.
- Decide whether explicit extracted-document send-to-model should be per-turn approval, config-only, or both.

## Open questions

- Should this runner live as PowerShell only, Java e2e harness, or both?
- Should approval-sensitive live audits use the same model/backend preflight as `run-capability-live-audit.ps1`?

## Related files

- `scripts/run-capability-live-audit.ps1`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/cli/repl/slash/PrivacyCommand.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
