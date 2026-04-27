# [done] Ticket: Minimal Runtime Failure Policy

Date: 2026-04-25
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/29-v1-scenario-pack.md`

Depends on / follows:
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`

## Why This Ticket Exists

Talos now has structured phase, contract, outcome, and verification slices.
The next architecture gap is failure discipline.

The loop already has important local cushions:

- hard iteration cap
- approval-denial stop
- duplicate failed edit short-circuit
- repeated edit failure suggestion
- redundant read suppression

Those are useful, but they are not yet a formal failure policy. Repeated
non-progress failure can still degrade into repeated model retries until the
hard iteration cap.

## Problem

The current failure behavior is still partly implicit:

- failure thresholds are scattered
- no-progress iterations are not tracked as a policy concept
- same-tool and same-path repeated failures are not centrally evaluated
- the final stop reason is not structured
- scenario `12` still proves only the baseline loop cap, not a controlled
  failure-policy stop

## Goal

Add a minimal `FailurePolicy` that stops repeated non-progress loops before the
hard iteration cap and records a structured decision.

This should not add planner behavior, shell/browser tools, MCP, background
autonomy, or broad semantic recovery.

## Scope

### In scope

- small `dev.talos.runtime.failure` package
- default policy thresholds for same-tool, same-path, and no-progress failures
- per-loop failure counts in `LoopState`
- structured `FailureDecision` exposed on `ToolCallLoop.LoopResult`
- controlled final-answer fallback when the policy stops the loop
- update scenario `12` from baseline loop-cap behavior to controlled failure
  policy behavior

### Out of scope

- complex reset-to-inspect implementation
- automatic reread retry sequencing
- user-visible phase/outcome trace command
- shell/browser/test-runner verification
- MCP server logic
- broad task repair planning

## Proposed Work

Add:

```text
src/main/java/dev/talos/runtime/failure/
```

Likely classes:

```text
FailureAction
FailureDecision
FailurePolicy
```

Initial actions:

```text
CONTINUE
ASK_USER
STOP_WITH_PARTIAL
```

Initial thresholds:

```text
maxSameToolFailures = 3
maxSamePathFailures = 3
maxNoProgressIterations = 3
```

The policy should stop before the hard iteration cap when:

- the same tool fails repeatedly
- the same path fails repeatedly
- several consecutive iterations produce no successful tool result

If mutations already succeeded, stop as `STOP_WITH_PARTIAL`. If no mutation
succeeded, stop as `ASK_USER`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/failure/`
- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/test/java/dev/talos/runtime/failure/`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/12-repeated-missing-path-stops-at-loop-cap.json`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.failure.*"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Then widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos manual verification is required:

- uninstall current Talos
- build `installDist`
- install Talos
- clear only the verified horror-synth session
- run the standard prompt sequence against `local/playground/horror-synth-site`
- review `local/manual-testing/test-output`

## Acceptance Criteria

- repeated same-path/tool/no-progress failures stop before the hard iteration
  cap
- policy stop reason is structured and exposed on `LoopResult`
- final answer says the loop stopped because of failure policy, not because the
  task completed
- existing approval-denial stop behavior remains unchanged
- partial mutation summary behavior remains unchanged
- JSON scenario `12` proves controlled failure-policy stop
- full tests and installed CLI verification pass before marking done

## Completion Notes

Implemented the first formal failure-policy slice:

- `FailurePolicy`
- `FailureDecision`
- `FailureAction`
- loop-state counters for same-tool, same-path, and no-progress failures
- `ToolCallLoop.LoopResult.failureDecision()`
- controlled failure-policy stop message before the hard iteration cap

Scenario `12` now proves early failure-policy stop instead of baseline
iteration-limit behavior.

This does not implement reset-to-inspect or automatic reread-before-retry
sequencing yet. Those remain future failure-discipline work.

## Verification Evidence

Focused checks passed:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.failure.*"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Wide checks passed:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos manual verification was run after uninstall/install against
`local/playground/horror-synth-site`. The standard prompt flow confirmed:

- clean session start
- read-only inspection stayed read-only
- selector grounding corrected unsupported model prose
- explicit edit reached approval
- denial prevented writes and stopped cleanly
- tracked playground files stayed unchanged

Observed medium-priority display debt:

- empty streamed ```json fences can still appear before tool-loop execution
- pre-tool speculative prose can appear before the controlled final answer

That was recorded separately in
`work-cycle-docs/tickets/done/talos-streaming-protocol-fence-and-pretool-prose-display.md`.
