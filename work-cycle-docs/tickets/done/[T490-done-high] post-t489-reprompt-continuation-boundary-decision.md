# [T490-done-high] Post-T489 Reprompt Continuation Boundary Decision

## Status

Done.

## Scope

T490 inspects `ToolCallRepromptStage` after T489 extracted request assembly
and decides the next implementation slice.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected-read behavior, tool execution, repair behavior,
trace wording, prompt wording, outcome wording, or final-answer behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `d35c2910`.

| Item | Measurement |
|---|---:|
| `ToolCallRepromptStage.java` | 884 lines |
| `ToolRepromptRequestBuilder.java` | 155 lines |
| Architecture baseline | 0 |

## Source Findings

After T489, `ToolCallRepromptStage` still owns several distinct clusters:

- top-level stop/continue orchestration;
- approval-denied and path-policy stop handling;
- static-web and expected-target progress decisions;
- read-only repair/mutation budget checks;
- context-budget fallback behavior;
- compact mutation continuation execution;
- chat reprompt execution and engine exception handling;
- failure-policy stop rendering;
- denied-mutation response-only synthesis;
- stale/empty edit repair prompt insertion;
- remaining full-rewrite and expected-target accounting.

The clearest remaining non-orchestration pocket is the context-budget and
compact-continuation fallback cluster:

- `stopAfterContextBudgetExceeded(...)`;
- `CompactMutationContinuationOutcome`;
- `tryCompactMutationContinuation(...)`.

This cluster is coherent because it owns what happens when a reprompt cannot
fit the local model context:

- record context-budget warning;
- fail pending action obligations when applicable;
- try compact mutation continuation;
- fall back to compact read-only evidence continuation;
- otherwise set deterministic context-budget failure text;
- record compact-continuation warnings/action obligations;
- stop deterministically when compact continuation returns no tool calls.

It is not just a helper move. It includes LLM calls and failure-state mutation,
so it should be extracted as a named runtime policy component with focused
tests.

## Decision

The next implementation ticket should be:

```text
[T491] Extract reprompt context budget handler
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandler
```

Preferred responsibilities:

- handle `EngineException.ContextBudgetExceeded` from reprompt attempts;
- preserve pending-action-obligation breach behavior;
- preserve compact mutation continuation behavior;
- preserve compact read-only evidence continuation fallback;
- preserve deterministic final context-budget answer/failure decision;
- preserve trace warning/action-obligation wording;
- preserve the current boolean result contract:
  - `true` means continue the tool loop;
  - `false` means stop the turn.

`ToolCallRepromptStage` should keep:

- deciding where context-budget handling is invoked;
- normal chat reprompt execution;
- non-context engine exception handling;
- high-level stop/continue orchestration.

## Rejected Immediate Work

### Extract Failure-Policy Stop Rendering

Rejected for T491.

It is smaller and less risky, but it does not address the bigger ownership
confusion now left in the stage.

### Extract Remaining Expected-Target Accounting

Rejected for T491.

`remainingExpectedMutationTargets(...)` mixes task-contract fallback target
extraction, workspace-operation plan path effects, basename safety, path
normalization, and static-repair exclusion. That should get its own decision
before any code move.

### Extract Denied-Mutation Response-Only Synthesis

Rejected for T491.

`responseOnlyAfterDeniedMutation(...)` performs a model call after policy stop.
It is sensitive behavior and should not be moved until the context-budget lane
is stable.

## Required T491 Tests

Start with RED tests for `ToolRepromptContextBudgetHandler`:

- context-budget failure with pending action obligation breaches the obligation
  and returns `false`;
- compact mutation continuation returning tool calls returns `true` and sets
  `state.currentNativeCalls`;
- compact mutation continuation returning no tool calls returns `false`, sets
  `FailureAction.ASK_USER`, and uses the existing deterministic no-action
  answer;
- when no compact continuation applies, context-budget handling sets the
  existing deterministic context-budget answer and clears native calls;
- `ToolCallRepromptStage` delegates context-budget handling and no longer owns
  `stopAfterContextBudgetExceeded(...)` or
  `tryCompactMutationContinuation(...)`.

Recommended focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*ContextBudget*" --tests "dev.talos.runtime.ToolCallLoopTest.*CompactMutationContinuation*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
