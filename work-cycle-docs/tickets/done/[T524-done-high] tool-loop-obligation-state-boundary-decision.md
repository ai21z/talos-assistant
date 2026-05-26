# [T524-done-high] Tool Loop Obligation State Boundary Decision

Status: done
Priority: high
Date: 2026-05-26
Branch: `T524`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `b3ddaf25`
Predecessor: `T523`

## Scope

T524 is a no-code inspection and decision ticket for the remaining
tool-loop obligation/state boundary after the `ToolCallRepromptStage` lane was
closed in T523.

This ticket intentionally does not extract code. The goal is to decide whether
there is a coherent implementation slice left in the reprompt/obligation area,
or whether the next move should leave this lane entirely.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `b3ddaf25`:

| File | Lines | Current role |
|---|---:|---|
| `ToolCallRepromptStage.java` | 143 | Ordered reprompt decision chain and final obligation selection before overlay continuation. |
| `LoopState.java` | 516 | Mutable loop state, pending-obligation lifecycle, breach enforcement, static repair invalid-write stops, and loop counters/evidence state. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure wording, and trace recording. |
| `StaticRepairTargetProgressAccounting.java` | 37 | Remaining full-rewrite static repair target calculation. |
| `ExpectedTargetProgressAccounting.java` | 93 | Remaining expected mutation target calculation and target-key normalization. |
| `ToolRepromptRequestBuilder.java` | 155 | Reprompt tool surface narrowing, prompt frame construction, and request controls. |
| `ToolCallLoop.java` | 531 | Parse/execute/reprompt loop orchestration and pending-obligation breach checkpoints. |

## Source Evidence

`ToolCallRepromptStage` still owns the final obligation selection block:

- calls `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state)`;
- calls `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(state)`;
- decides `staticRepairObligationActive`;
- decides `expectedTargetObligationActive`;
- raises `PendingActionObligation.staticRepairTargets(...)`;
- raises `PendingActionObligation.expectedTargets(...)`;
- clears the pending obligation when neither remains active;
- calls `ToolRepromptRequestBuilder.toolSpecs(...)` with the active flags;
- passes remaining targets and the selected tool surface to
  `ToolRepromptOverlayContinuation.execute(...)`.

That is a real ownership boundary: it is the point where target accounting
becomes loop state and tool-surface narrowing.

`PendingActionObligation` is not merely data. It also owns:

- target normalization and deduplication;
- obligation kind labels;
- user-facing failure reason/answer text;
- raised/breached trace recording.

`LoopState` owns breach enforcement:

- no executable tool call while an obligation is pending;
- invalid expected-target mutation attempts;
- invalid old-string miss, append-line, and expected-target scope repair calls;
- invalid static-repair write calls;
- static selector repair invalid-write stops;
- failure decision mutation and native-call clearing.

`ToolCallLoop` calls that breach enforcement before execution and before
falling out of the loop when the model returns no executable calls.

## Decision

The next implementation ticket should extract the obligation selection and
tool-surface selection glue from `ToolCallRepromptStage`.

Recommended next ticket:

```text
[T525] Extract tool reprompt obligation selector
```

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolRepromptObligationSelector
```

Recommended API shape:

```java
record Selection(
        List<String> remainingRepairTargets,
        List<String> remainingExpectedTargets,
        boolean staticRepairObligationActive,
        List<ToolSpec> repromptToolSpecs
) {}

static Selection select(
        LoopState state,
        ToolCallExecutionStage.IterationOutcome outcome
)
```

The selector should:

1. compute remaining static-repair targets;
2. compute remaining expected mutation targets;
3. decide static-repair obligation activity;
4. decide expected-target obligation activity;
5. raise, replace, or clear `PendingActionObligation`;
6. choose the narrowed reprompt tool specs through
   `ToolRepromptRequestBuilder.toolSpecs(...)`;
7. return only the data `ToolCallRepromptStage` needs for
   `ToolRepromptOverlayContinuation.execute(...)`.

`expectedTargetObligationActive` does not need to be exposed if the selector
only uses it to choose the pending obligation and reprompt tool specs.

## Why This Is The Correct Slice

The selector is a coherent owner because it owns one transition:

```text
target progress facts -> pending obligation state + next reprompt tool surface
```

Today that transition is embedded in the reprompt stage. The stage should own
ordering, not the details of how target progress becomes pending obligation
state.

This slice is also bounded:

- it does not change tool execution;
- it does not change failure wording;
- it does not change trace wording;
- it does not change pending-obligation breach enforcement;
- it does not change static repair target accounting;
- it does not change expected target accounting;
- it does not change prompt construction or chat execution.

## Rejected Alternatives

### Strengthen `PendingActionObligation` first

Rejected for T525.

Reason: `PendingActionObligation` already owns the value, failure text, and
trace events. Making it compute remaining targets or choose tool specs would
mix model-state facts, execution outcomes, and request-building policy into a
value object.

### Move breach enforcement out of `LoopState`

Rejected for T525.

Reason: breach enforcement is larger and safety-sensitive. It mutates
`failureDecision`, `currentText`, and `currentNativeCalls`, and it deliberately
stops before approval when the model ignores required targets. Moving it should
be a separate design ticket after the selector boundary is clean.

### Move tool-surface narrowing out of `ToolRepromptRequestBuilder`

Rejected for T525.

Reason: `ToolRepromptRequestBuilder.toolSpecs(...)` already owns the primitive
tool filtering. The selector should decide which obligation mode is active and
ask the builder for the narrowed surface; it should not duplicate filtering.

### Leave obligation selection in the stage indefinitely

Rejected.

Reason: after T517 through T523, this is the remaining non-trivial state
transition inside `ToolCallRepromptStage`. Keeping it there would preserve the
architectural ambiguity T523 identified: the stage is both orchestrator and
obligation-state selector.

## Explicit Non-Goals For T525

Do not combine the selector extraction with:

- `LoopState.failPendingActionObligationAfterInvalidToolCalls(...)`;
- `LoopState.failPendingActionObligationAfterNoExecutableToolCalls()`;
- `LoopState.failStaticRepairAfterInvalidWriteContent(...)`;
- `LoopState.failStaticSelectorRepairAfterInvalidWriteContent(...)`;
- `PendingActionObligation.failureReason(...)`;
- `PendingActionObligation.failureAnswer(...)`;
- `PendingActionObligation.recordRaised()` or `recordBreached(...)`;
- `StaticRepairTargetProgressAccounting`;
- `ExpectedTargetProgressAccounting`;
- `ToolRepromptRequestBuilder.messages(...)`;
- `ToolRepromptOverlayContinuation`.

T525 should preserve exact final-answer wording, failure reasons, trace events,
pending-obligation kinds, tool narrowing, and loop behavior.

## Expected T525 Verification Shape

T525 should use a RED/GREEN ownership test before implementation:

- `ToolCallRepromptStage` delegates obligation selection to
  `ToolRepromptObligationSelector.select(...)`.
- `ToolCallRepromptStage` no longer directly calls
  `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(...)`.
- `ToolCallRepromptStage` no longer directly calls
  `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(...)`.
- `ToolCallRepromptStage` no longer directly calls
  `PendingActionObligation.staticRepairTargets(...)`.
- `ToolCallRepromptStage` no longer directly calls
  `PendingActionObligation.expectedTargets(...)`.
- The selector owns those calls and still delegates primitive tool filtering to
  `ToolRepromptRequestBuilder.toolSpecs(...)`.

Focused behavior tests should cover:

- static full-rewrite repair keeps only `talos.write_file`;
- expected-target progress keeps `talos.write_file` and `talos.edit_file`;
- no remaining targets clears the pending obligation;
- existing pending obligation keeps static repair active when static repair
  context remains;
- expected-target obligation is active after mutation progress and inactive
  before mutation progress;
- fallback to original tools still works when mutating tools are unavailable.

Required verification:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- The post-T523 obligation/state boundary is inspected from fresh beta.
- No code changes are made.
- The next implementation ticket is selected from source evidence.
- The selected next ticket is bounded to obligation selection only.
- Rejected broader state rewrites are documented.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 13 executed, 1 up-to-date).
