# T313 - TalosBench Piped Approval Drift On Missing Approval Prompt

Status: implemented-awaiting-evidence - default piped approval execution fails closed; synchronized full prompt-bank path remains open
Severity: high
Release gate: yes for full live-audit evidence
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

`tools/manual-eval/run-talosbench.ps1` drives installed Talos through redirected stdin. For approval-sensitive cases it writes the user prompt, then pre-writes approval input such as `a`, `y`, or `n`.

If the model produces no mutating tool call, emits malformed tool-call JSON, or otherwise never reaches an approval prompt, the queued approval input can be consumed as the next normal user request. The subsequent `/last trace` then describes the approval token turn instead of the audited prompt.

That contaminates evidence. It can make a real first-turn failure look like an ordinary trace assertion mismatch, and it can hide the original prompt's trace behind `User Request: a`.

## Evidence from current code

- `New-TalosBenchInputLines` in `tools/manual-eval/run-talosbench.ps1` still can construct one redirected-stdin stream for the prompt, approval inputs, `/last trace`, and `/q`.
- `Invoke-TalosProcess` still pipes that whole stream into the installed `talos.bat` process when the operator explicitly opts into piped approval input.
- This is not the synchronized Java approval harness and not true PTY/JLine coverage.
- The runner now contains `Test-ApprovalInputDrift`, which scans transcripts for configured approval inputs later appearing as traced `User Request` blocks and fails the case explicitly.
- The runner now contains `Get-TalosBenchManualExecutionGate`, which returns `SYNC_REQUIRED` for approval-sensitive manual cases when `-IncludeManualRequired` is used without `-AllowPipedApprovalInputs`.

## Evidence from tests/audits

- Qwen full TalosBench r1:
  - `local/manual-testing/talosbench-full-qwen-20260519-r1/20260519-163138/full-audit-mkdir-tool-probe.txt`
  - First audited turn: `FILE_CREATE`, `mutationAllowed=true`, visible tool `talos.mkdir`.
  - Model result: invalid tool-call payload, no action taken, no approval prompt.
  - Drift: queued approval input `a` became the second user request; `/last trace` reported `User Request: a` with `READ_ONLY_QA`.
- Focused Qwen rerun passed:
  - `local/manual-testing/talosbench-qwen-mkdir-20260519-r1/20260519-163730/summary.md`.
- Full Qwen rerun passed:
  - `local/manual-testing/talosbench-full-qwen-20260519-r2/20260519-163747/summary.md`.
- Full GPT-OSS run passed:
  - `local/manual-testing/talosbench-full-gptoss-20260519-r3/20260519-162507/summary.md`.
- Fresh runner checks after adding the contamination detector:
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` passed.
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed.
- Fresh runner checks after the fail-closed execution gate:
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` passed.
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed.
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId full-audit-mkdir-tool-probe -IncludeManualRequired -WorkspaceRoot local/manual-workspaces/talosbench-sync-required-selftest -TranscriptRoot local/manual-testing/talosbench-sync-required-selftest` returned `SYNC_REQUIRED` and exit code `1`, proving the runner now refuses to pre-feed approval input by default.
- Fresh full-gate evidence after the detector and static-web verifier-context fix:
  - `./gradlew.bat clean check e2eTest --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed.
  - Runtime artifact scans passed over `build/reports,build/test-results`, `work-cycle-docs/reports,work-cycle-docs/tickets`, and `build/synchronized-approval-audit/artifacts`.
- Fresh evidence-lane rebaseline after sink hardening:
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` passed.
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed and validated 41 cases.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208/artifacts" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon` passed.
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208,local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon` passed.
  - The synchronized rebaseline records 32 scripted scenarios and does not make a redirected-stdin full prompt-bank claim.

## User impact

Maintainers can misread audit evidence. A failed first turn can be partially overwritten by a later accidental approval-token turn, which makes the audit harder to debug and weakens release confidence.

## Product risk

High for release evidence. This does not prove a runtime privacy or mutation-safety failure, but it can corrupt the evidence used to decide whether those boundaries passed.

## Runtime boundary affected

Audit-runner input synchronization, approval evidence integrity, `/last trace` evidence integrity, and full prompt-bank release reporting.

## Non-goals

- Do not weaken approval requirements.
- Do not remove approval-sensitive cases from TalosBench.
- Do not claim redirected stdin is true PTY/JLine coverage.
- Do not treat a rerun pass as proof that the runner design is synchronized.

## Required behavior

- If an expected approval prompt does not appear, the runner must fail the case with first-turn evidence, not feed approval text into the next user turn.
- If approval input is detected as a traced user request, the case must be marked failed with a synchronization-specific diagnostic.
- Full release evidence must distinguish redirected-stdin TalosBench runs from synchronized Java-harness runs and manual true PTY/JLine runs.

## Proposed implementation

Slices completed in working tree:

- Added `Test-ApprovalInputDrift` to `tools/manual-eval/run-talosbench.ps1`.
- Added a self-test fixture matching the Qwen r1 contamination shape.
- Cases now add an `Approval synchronization failed:` note when approval drift is detected.
- Added `-AllowPipedApprovalInputs` as an explicit exploratory opt-in.
- Added `Get-TalosBenchManualExecutionGate`.
- Approval-sensitive cases with configured approval input now report `SYNC_REQUIRED` and exit non-zero when `-IncludeManualRequired` is used without the explicit piped-approval opt-in.
- `tools/manual-eval/README.md` now directs release evidence to the synchronized approval harness and labels piped approval input as exploratory only.

Remaining implementation:

- Replace approval-sensitive full prompt-bank execution with a synchronized process driver that waits for an approval prompt before sending the approval response, or route those cases through the Java synchronized approval harness.
- Preserve full transcript and first-turn trace evidence when the expected prompt never appears.
- Keep true PTY/JLine manual audit separate unless a dedicated PTY/ConPTY harness is added.

## Tests

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
- Synthetic transcript with `User Request: a` for a case whose approval input is `a` fails with the approval synchronization diagnostic.
- Approval-sensitive manual cases return `SYNC_REQUIRED` unless `-AllowPipedApprovalInputs` is explicitly supplied.

## Acceptance criteria

- Approval-sensitive TalosBench cases no longer pre-feed approval input blindly by default.
- Missing approval prompt is reported as a synchronization failure with original prompt evidence preserved.
- No full-audit report claims synchronized approval coverage from redirected stdin alone.
- True PTY/JLine coverage is either manually executed with the prepared runbook or automated with a real terminal harness.

## Remaining blockers

- Current default guard prevents contamination for normal `-IncludeManualRequired` runs by returning `SYNC_REQUIRED`, but an explicit `-AllowPipedApprovalInputs` exploratory mode still exists and must not be used as release evidence.
- True PTY/JLine audit remains manual.
- Full prompt-bank evidence is stronger after historical GPT-OSS/Qwen passes, but still not a synchronized terminal audit. The next release-grade prompt-bank pass must be lane-labeled: safe redirected-stdin cases separate from synchronized approval cases and manual true-PTY cases.
- 2026-05-20 update: `run-talosbench.ps1` now has a strict safe-lane
  evidence mode for non-approval cases. This mode uses `/debug prompt on`,
  saves `/last trace`, `/prompt-debug save`, and `/session save` after each
  natural-language prompt, and records per-case input/transcript/workspace
  status artifacts. It does not change the T313 approval rule: approval cases
  still require synchronized approval or true PTY/manual evidence.
- Fresh lane evidence:
  - Strict safe redirected-stdin lane passed for GPT-OSS and Qwen.
  - Synchronized approval audit passed separately.
- True PTY/JLine lane passed via the completed manual packet
  `local/manual-testing/true-pty-manual-20260520-r1/artifacts`.

## 2026-05-20 true PTY/manual lane update

The true-terminal lane now has completed evidence for the current audit wave:

```text
Audit id: true-pty-manual-20260520-r1
Validator: validateSynchronizedApprovalPtyManualAudit PASS
Artifact scan: PASS
```

This does not weaken the T313 rule. Approval-sensitive redirected-stdin
TalosBench runs still fail closed by default; true PTY evidence came from a real
interactive terminal transcript, not piped approval input.

## 2026-06-07 0.10.0 audit rule

For the next `0.10.0` full prompt-bank audit, approval-sensitive evidence must
come from synchronized approval handling or manual/PTY capture. Redirected stdin
may still be used for safe non-approval cases, but the report must label that
lane separately and must not treat pre-fed approval input as release-grade
approval evidence.

This ticket remains open until the `0.10.0` audit packet proves that approval
tokens cannot drift into subsequent user turns for any approval-sensitive case
used in release evidence.

## Open questions

- Should approval-sensitive TalosBench cases be split into a separate synchronized runner instead of overloading the current PowerShell runner?
- Should the synchronized Java runner import the TalosBench JSON directly so there is only one prompt bank?
- Is a Windows ConPTY dependency acceptable for release-audit automation, or should manual PTY evidence remain the standard?

## Related files

- `tools/manual-eval/run-talosbench.ps1`
- `tools/manual-eval/talosbench-cases.json`
- `src/e2eTest/java/dev/talos/harness/SynchronizedCliProcessDriver.java`
- `src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditRunner.java`
- `work-cycle-docs/reports/synchronized-approval-runner-blocker-investigation.md`
- `work-cycle-docs/tickets/open/[T306-open-high] synchronized-approval-live-audit-runner.md`
