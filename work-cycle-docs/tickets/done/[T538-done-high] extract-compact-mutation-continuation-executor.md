# [T538-done-high] Extract Compact Mutation Continuation Executor

Status: done
Priority: high
Date: 2026-05-27
Branch: `T538`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `143acd36`
Predecessor: `T537`

## Scope

T538 implements the ownership boundary selected by T537.

`ToolRepromptContextBudgetHandler` remains the context-budget fallback router.
The compact mutation continuation execution path moves to
`CompactMutationContinuationExecutor`.

## Implementation

Added:

- `CompactMutationContinuationExecutor`
- `CompactMutationContinuationExecutorTest`

Moved out of `ToolRepromptContextBudgetHandler`:

- compact mutation continuation plan lookup;
- compact LLM call execution;
- compact mutation response application to `LoopState`;
- existing compact mutation trace warnings/action-obligation records;
- existing no-tool terminal failure reason and deterministic no-action answer;
- existing `NOT_APPLICABLE`, `CONTINUE_LOOP`, and `STOP_TURN` outcome
  classification.

Preserved in `ToolRepromptContextBudgetHandler`:

- pending action obligation failure precedence;
- context-budget fallback ordering;
- compact read-only evidence fallback;
- deterministic context-budget stop;
- public handler entry points used by reprompt continuations and mutation
  evidence budget handling.

## Explicit Non-Changes

T538 does not change:

- compact mutation prompt text;
- compact mutation tool schemas;
- compact continuation tool-choice controls;
- trace warning codes/details;
- fallback order;
- compact read-only evidence continuation;
- `ToolCallLoop.finalizeAnswer(...)`;
- normal reprompt result application;
- task contract or expected-target behavior.

## Verification

RED/GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactMutationContinuationExecutorTest" --no-daemon
```

- RED: failed before implementation because
  `CompactMutationContinuationExecutor` did not exist.
- GREEN: passed after implementation.

Focused regression tests:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.runtime.toolcall.CompactMutationContinuationExecutorTest" `
  --tests "dev.talos.runtime.toolcall.ToolRepromptContextBudgetHandlerTest" `
  --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" `
  --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceBudgetGateTest" `
  --tests "dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuationTest" `
  --no-daemon
```

- Passed.

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

- Passed.

Final gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T538 merges, inspect the post-extraction tool-loop state before choosing
T539. Do not assume compact read-only evidence continuation, final answer
finalization, or normal reprompt result application is the next implementation
slice without source inspection.
