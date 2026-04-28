# [T39-done-high] Ticket: Implement Bounded Repair Controller V1
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T38 bounded repair controller design ticket

## Context

Current repair behavior includes static verification context and loop stop
policies, but repair is not yet owned by a dedicated policy/controller. A v1
repair controller should reduce blind retry loops while keeping final answers
truthful.

## Goal

Implement bounded repair strategy using existing `StaticVerificationRepairContext`
and `ToolCallLoop` seams.

## Non-Goals

- Do not add shell/browser execution.
- Do not add multi-agent repair.
- Do not bypass approval, permission, checkpoint, or phase policies.
- Do not claim runtime/browser validation from static checks.

## Implementation Notes

- Avoid blind retry loops.
- A failed static verification can produce one bounded repair plan.
- Repeated failures stop cleanly.
- Verifier findings should be passed into repair.
- Final answer must remain truthful.
- Prefer small policy/controller classes over adding more branching to
  `AssistantTurnExecutor`.

## Acceptance Criteria

- No blind retry loops.
- Failed static verification can produce one bounded repair plan.
- Repeated failures stop cleanly.
- Successful repair is verified before being reported complete.
- Failed repair reports remaining issues precisely.
- Final answer remains truthful.
- Tests cover successful repair, failed repair, and no-progress stop.
- Manual Talos check covers a broken small web app repair flow.

## Tests / Evidence

Run focused repair/controller tests first, then:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual installed Talos verification is required.

## Work-Test Cycle Notes

Use the inner dev loop while implementing. This is runtime-sensitive and should
not begin until T38 is complete.

## Known Risks

- Repair controller work can become large. Keep v1 bounded to post-static
  verification failure and invalid edit/no-progress loops.
- Repair after verification failure still depends on model quality; the harness
  must preserve truthful partial/failed outcomes.

## Current Code Read

- `docs/architecture/06-bounded-repair-controller.md`
- `src/main/java/dev/talos/runtime/verification/StaticVerificationRepairContext.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`

## Implementation Summary

- Added `dev.talos.runtime.repair` with:
  - `RepairPolicy`
  - `RepairPlan`
  - `RepairPlanStep`
  - `RepairAttemptBudget`
  - `RepairDecision`
  - `RepairInstruction`
  - repair kind/status/step enums
- Moved static-verification repair planning behind `RepairPolicy`.
- Kept `StaticVerificationRepairContext` as a compatibility facade.
- Routed stale-edit and empty-edit repair instructions through `RepairPolicy`.
- Recorded planned repair decisions in `LocalTurnTrace`.
- Updated `/last trace` to show repair status/summary.
- Preserved existing approval, permission, checkpoint, and verification gates.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

Initial red test:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
```

Result: FAIL as expected before implementation because the repair policy/model
types did not exist.

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.staticVerificationRepairRetryPromptIncludesVerifierFindings" --no-daemon
```

Result: PASS.

Focused trace display test:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest.traceViewIncludesLocalTraceWhenTurnHasTraceId" --no-daemon
```

Result: PASS.

Focused e2e:

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext" --no-daemon
```

Result: PASS.

Full e2e:

```powershell
./gradlew.bat e2eTest --no-daemon
```

Result: PASS.

Hard gate:

```powershell
./gradlew.bat check --no-daemon
```

First result: FAIL on known pre-existing flaky
`ToolCallLoopP0Test > PartialSuccessRepromptTests > repromptsAfterPartialSuccessMixedMutationBatch`.

Isolation rerun:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopP0Test*PartialSuccessRepromptTests*repromptsAfterPartialSuccessMixedMutationBatch" --no-daemon
```

Result: PASS.

Hard gate rerun:

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Workspace: `local/manual-workspaces/T39/`

Model: `qwen2.5-coder:14b`

Prompt 1:

```text
This BMI page is broken. Fix it so it works as a 3-file webpage. Use the local files and apply the changes. If edit_file is fragile, overwrite the small files with complete corrected versions.
```

Approval choice: `a`

Prompt 2:

```text
Fix the remaining static verification problems now. If edit_file is fragile, overwrite the small files with complete corrected versions.
```

Observed tools: `write_file`

Files changed: `index.html`, `style.css`, `script.js`

Output file: `local/manual-testing/T39-output.txt`

Pass/fail: PASS for T39 harness behavior.

Notes:

- Both turns stayed mutation-capable (`FILE_CREATE`, `mutationAllowed=true`).
- Mutations were approval/checkpoint guarded.
- The live model did not fully repair the app and drifted between
  `styles.css`/`style.css` and `scripts.js`/`script.js`.
- Static verification reran and kept the task incomplete with precise
  remaining problems.
- `/last trace` showed `Repair: PLANNED - STATIC_VERIFICATION_REPAIR ...`.
- Talos did not claim the repair was complete.

## Known Follow-Ups

- Live-model repair quality still needs improvement; the controller now makes
  the repair attempt bounded and traceable, but does not guarantee the model
  completes every static web repair.
