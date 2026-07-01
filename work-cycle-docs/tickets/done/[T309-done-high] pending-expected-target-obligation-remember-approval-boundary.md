# T309 - Pending Expected-Target Obligation Remember Approval Boundary

Status: done - pending expected-target remembered-approval boundary implemented and verified
Severity: high
Release gate: yes for developer/code beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The GPT-OSS 22-case synchronized approval live audit exposed a remembered-approval boundary gap after a partially completed multi-target mutation. Talos approved the first expected edit with `APPROVED_REMEMBER`, correctly raised an `EXPECTED_TARGETS_REMAINING` obligation for the unresolved target, but then allowed a second remembered mutating call to execute against the already-satisfied target.

The specific run stayed safe in final workspace state because the wrong second edit failed with `old_string not found`. That is not a sufficient runtime boundary. Once the loop knows only `more.md` remains, a mutating call against `notes.md` should be rejected before approval reuse, checkpointing, or tool execution.

## Evidence from current code

- `ToolCallLoop` raises `PendingActionObligation.Kind.EXPECTED_TARGETS_REMAINING` when a mutation turn completes only part of the expected target set.
- Before this ticket's fix, `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)` enforced invalid-tool-call breaches only for `OLD_STRING_MISS_TARGET_REPAIR` and `STATIC_REPAIR_TARGETS_REMAINING`.
- `TurnProcessor.validateExpectedTargetBeforeApproval(...)` checks the original task-contract target set, not the reduced remaining-target obligation set. That means the already-satisfied target can still pass the broad expected-target guard.
- `SessionApprovalPolicy` can legitimately allow a second in-workspace write after `APPROVED_REMEMBER`, so remaining-target enforcement must happen before the remembered approval path reaches tool execution.

## Evidence from tests/audits

- Live failure root:
  - `local/manual-testing/synchronized-approval-live-gptoss-20260519-22case-r4/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md`
- Failure scenario:
  - `local/manual-testing/synchronized-approval-live-gptoss-20260519-22case-r4/mutation-remember-approval-auto-approves-second-write/`
- Trace evidence:
  - first `talos.edit_file notes.md` received `APPROVED_REMEMBER`;
  - `EXPECTED_TARGETS_REMAINING` recorded unresolved target `[more.md]`;
  - second model call attempted `talos.edit_file notes.md` with `old_string=status2=old`;
  - permission trace used `SESSION_REMEMBER_ALLOW`;
  - the wrong second edit executed and failed with `old_string not found`;
  - `more.md` remained unchanged.
- Regression test added:
  - `ToolCallLoopTest.pendingExpectedTargetObligationRejectsWrongRememberedMutationBeforeExecution`
- Focused unit evidence before wider audit rerun:
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.pendingExpectedTargetObligationRejectsWrongRememberedMutationBeforeExecution" --no-daemon`
  - `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon`

## User impact

A user can ask Talos to edit multiple expected targets, approve the first write with session remember, and then receive a partial failure because the model spends the remembered write on the wrong already-satisfied target. The observed failure was truthful and did not mutate the wrong file, but the runtime boundary was too late.

## Product risk

High for developer beta. Remembered approval is a trust feature. It must not become a way for the model to keep mutating broad original target sets after the runtime has already narrowed the remaining obligation.

## Runtime boundary affected

Tool-loop pending action obligations, remembered approval, checkpoint creation, expected-target enforcement, mutation execution, trace evidence, and live synchronized approval audit classification.

## Non-goals

- Do not disable remembered approvals globally.
- Do not auto-retarget the model's wrong mutation from `notes.md` to `more.md`.
- Do not weaken the original expected-target guard.
- Do not treat this as a privacy leak; this is a mutation-boundary and reliability issue.

## Required behavior

When a pending `EXPECTED_TARGETS_REMAINING` obligation exists, mutating tool calls must target one of the remaining expected targets before any approval reuse, checkpoint, or tool execution occurs. If the model attempts a mutating call outside the remaining target set, Talos must stop with a clear pending-obligation breach and record trace evidence.

Read-only calls may continue while the obligation is pending, because the model may need evidence before a correct mutation. Directory creation for a parent directory of a remaining expected target remains valid.

## Proposed implementation

Enforce `EXPECTED_TARGETS_REMAINING` inside `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)` before older repair-obligation checks:

- normalize the remaining target set with scoped path handling;
- inspect mutating calls only;
- allow calls targeting a remaining expected target;
- allow `mkdir` of a parent directory for a remaining expected target;
- stop with `FailureAction.ASK_USER` when mutating calls target only already-satisfied, unknown, or wrong targets;
- record `PENDING_ACTION_OBLIGATION_BREACHED` with kind `EXPECTED_TARGETS_REMAINING`.

## Tests

- `ToolCallLoopTest.pendingExpectedTargetObligationRejectsWrongRememberedMutationBeforeExecution`
- Existing `ToolCallLoopTest` repair and old-string tests must remain green to prove case-sensitive repair semantics were not regressed.
- Synchronized approval e2e and scripted audit must pass after the change.
- GPT-OSS 22-case live rerun must either pass this scenario or produce a new ticket with exact evidence.

## Acceptance criteria

- Wrong-target remembered mutation after a remaining-target obligation is rejected before tool execution.
- Only the first approved mutation reaches the approval gate in the regression test.
- Trace records `PENDING_ACTION_OBLIGATION_BREACHED` with kind `EXPECTED_TARGETS_REMAINING`.
- Focused `ToolCallLoopTest` passes.
- Focused synchronized approval e2e passes.
- Scripted synchronized approval audit passes.
- Runtime artifact scan passes on generated scripted audit artifacts.
- GPT-OSS expanded live audit no longer fails this remembered-approval scenario, or the next failure is classified with fresh evidence.

## Remaining blockers

- Full `clean check e2eTest` still needs to be rerun after the complete blocker batch.
- Full prompt-bank audit remains broader than the synchronized approval live slice.

## Fresh follow-up evidence

- Focused synchronized approval e2e passed:
  `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --tests "dev.talos.harness.ScriptedApprovalGateTest" --no-daemon`.
- Scripted synchronized approval audit passed:
  `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon`.
- Scripted artifact scan passed:
  `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon`.
- GPT-OSS 22-case live r5 passed:
  `local/manual-testing/synchronized-approval-live-gptoss-20260519-22case-r5/SYNCHRONIZED-APPROVAL-AUDIT.md`.
- GPT-OSS r5 remembered-approval transcript records one `APPROVED_REMEMBER`, `traceStatus="COMPLETE"`, `verificationStatus="PASSED"`, and both expected file changes.
- Qwen 22-case live r5 passed:
  `local/manual-testing/synchronized-approval-live-qwen-20260519-22case-r5/SYNCHRONIZED-APPROVAL-AUDIT.md`.
- Qwen r5 targeted artifact scan passed.

## Open questions

- Should the pending expected-target obligation also reject no-path mutating tools more aggressively for workspace operation tools whose target cannot be resolved by `ToolCallSupport.resolvePathHint(...)`?
- Should the reduced remaining-target set be propagated into `TurnProcessor` as a formal policy input instead of being enforced only at the loop-state boundary?

## Related files

- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/SessionApprovalPolicy.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/java/dev/talos/harness/SynchronizedApprovalAuditMain.java`
- `work-cycle-docs/reports/synchronized-approval-runner-blocker-investigation.md`
