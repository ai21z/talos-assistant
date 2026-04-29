# [T43-done-medium] Ticket: Protected Read Approval Risk and Outcome Labels
Date: 2026-04-29
Priority: medium
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual testing confirmed protected `.env` reads ask for approval and do not
leak content when denied. The behavior is safe, but the user-facing labels and
trace outcome are confusing.

Observed prompt:

```text
Read .env and tell me what it says.
```

Observed approval dialog:

```text
Action: read only operation: talos.read_file
Risk:   write
```

After denial, the final answer said it could not read the file, but the local
trace rendered:

```text
Outcome: COMPLETE (READ_ONLY_ANSWERED)
```

## Problem

Protected read approval is safe, but the risk label says `write`, and denied
read-only tool calls can render as completed read-only answers in the local
trace. That weakens trust in the trace and approval UX.

## Goal

Protected reads should show an accurate sensitive-read risk/category, and
approval-denied read turns should be classified as blocked/not completed rather
than complete.

## Scope

In scope:
- Approval dialog risk text for protected read tools.
- Turn outcome/trace classification for denied read-only tool calls.
- Tests covering protected-read denial.

Out of scope:
- Changing protected path defaults.
- Allowing protected reads without approval.
- Permission UI redesign.

## Proposed Work

- Review `ToolRiskLevel`, `PermissionDecision`, and approval rendering for
  read-only protected paths.
- Add or adjust an outcome classification for approval-denied read-only turns.
- Ensure trace and `/last trace` show blocked/denied instead of complete.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/policy/`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- tests under `src/test/java/dev/talos/`

## Test / Verification Plan

- Unit test for protected read approval metadata.
- Turn/executor test for denied `read_file .env`.
- Manual installed Talos check with denied `.env` read.

## Acceptance Criteria

- Protected read approval no longer displays `Risk: write`.
- Denied protected read does not reveal file content.
- Trace/outcome does not report the turn as complete/read-only answered.
- Existing protected mutation denial still denies before approval.

## Current Code Read

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/runtime/policy/DeclarativePermissionPolicy.java`
- `src/main/java/dev/talos/runtime/policy/PermissionDecision.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/test/java/dev/talos/runtime/TurnProcessorPermissionPolicyTest.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`
- `src/e2eTest/resources/scenarios/66-protected-read-requires-approval.json`

## Planned Tests

- Add approval-detail coverage proving protected reads are labeled as
  `sensitive read`, not `write`.
- Add executor/e2e coverage for denied protected `.env` read.
- Add `/last trace` rendering coverage proving denied protected reads are
  blocked/denied rather than complete/read-only answered.

## Implementation Summary

- Labeled protected `read_file` approval prompts as `protected read` with
  `Risk: sensitive read` instead of deriving a misleading write risk from the
  protected-path target detail.
- Added deterministic outcome shaping for denied protected reads so the final
  answer says protected content was not read because approval was denied.
- Classified denied protected reads as `BLOCKED_BY_APPROVAL` in task outcome
  and local trace while preserving mutation status as not requested.
- Added deterministic e2e coverage for denied protected `.env` reads.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

- `./gradlew.bat test --tests "dev.talos.runtime.ApprovalGateTest.cliApprovalGateLabelsProtectedReadAsSensitiveRead" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.runtime.TurnProcessorPermissionPolicyTest.protectedReadAsksBeforeReading" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.deniedProtectedReadIsClassifiedAsApprovalBlockedAndSanitized" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest.rendersDeniedProtectedReadAsBlockedApprovalOutcome" --no-daemon` - PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.deniedProtectedReadProducesBlockedOutcome" --no-daemon` - PASS
- `./gradlew.bat test --no-daemon` - PASS
- `./gradlew.bat e2eTest --no-daemon` - PASS
- `./gradlew.bat check --no-daemon` - PASS

Note: two focused Gradle tests were initially run in parallel and collided on
Gradle's shared `build/test-results/test/binary` output. They were rerun
sequentially and passed.

## Manual Talos Check Result

Command: installed Talos from fresh `clean installDist` build
Workspace: `local/manual-workspaces/T43/`
Model: `qwen2.5-coder:14b`
Prompt: `Read .env and tell me what it says.`
Approval choice: denied (`n`)
Observed tools: `talos.read_file` attempted once and failed after approval denial
Files changed: none
Output file: `local/manual-testing/T43-output.txt`
Pass/fail: PASS
Notes: Approval displayed `Action: protected read: talos.read_file` and
`Risk: sensitive read`; the final answer did not reveal `.env` content; `/last`
reported `Outcome: BLOCKED_BY_APPROVAL`; local trace reported
`Outcome: BLOCKED (BLOCKED_BY_APPROVAL)`.

## Known Follow-Ups

- None for T43. T44 remains the next open 0.9.8 scope ticket for live BMI repair
  competence.
