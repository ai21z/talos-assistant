# [T537-done-high] Post Terminal Response State Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T537`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `a410b62e`
Predecessor: `T536`

## Scope

T537 is a no-code inspection ticket after T536 added terminal response helpers
to `LoopState`.

The goal is to decide the next ownership move from current source evidence,
not continue mechanically extracting from the tool loop.

## Current Source Shape

Measured from fresh `origin/v0.9.0-beta-dev` at `a410b62e`.

The post-T536 assignment inventory was inspected with:

```powershell
rg -n "state\.currentText\s*=|state\.currentNativeCalls\s*=|state\.failureDecision\s*=|finishWithAnswer|stopWithFailure" src/main/java/dev/talos/runtime src/test/java/dev/talos/runtime
```

Remaining direct production assignments are now concentrated in these buckets:

| Bucket | Files | Decision |
|---|---|---|
| Loop fallback/finalization | `ToolCallLoop.java` | Keep in `ToolCallLoop` for now. Unfinished-tool suppression, iteration-limit suffixing, tool-call stripping, suspicious-HTML stripping, and protected-content sanitization are final loop orchestration concerns. |
| Normal reprompt result application | `ToolRepromptChatExecutor.java` | Keep in the chat executor. It applies raw model stream results and determines whether the loop continues. This is not terminal response state. |
| Compact mutation continuation execution | `ToolRepromptContextBudgetHandler.java` | Next coherent implementation boundary. Planning is already in `CompactMutationContinuationPlanner`, but execution, result state application, trace warnings, and no-tool failure handling still live in the context-budget handler. |
| Compact read-only evidence continuation | `CompactReadOnlyEvidenceContinuation.java` | Keep separate for now. It is already owned by its own class and combines answer synthesis, tool-call rejection, pending-obligation clearing, and trace warning. |
| Repair setup | `ToolRepromptPathPolicyBlockedDecision.java` | Keep explicit. It creates a repair native call and intentionally continues the loop. |
| Non-terminal failure signal | `ToolFailureIterationSignals.java` | Keep explicit. It updates failure policy state, not terminal answer state. |

## Decision

Do not extract final-answer finalization yet.

Do not move compact read-only evidence continuation yet.

The next implementation ticket should be:

```text
[T538] Extract compact mutation continuation executor
```

T538 should move only the compact mutation continuation execution path out of
`ToolRepromptContextBudgetHandler` into a focused owner, likely:

```text
CompactMutationContinuationExecutor
```

Expected ownership:

- accept `LoopState`, retry name, reason, and base tool specs;
- ask `CompactMutationContinuationPlanner` for a plan;
- execute the compact LLM call;
- apply the compact mutation continuation result to `LoopState`;
- record the existing trace warnings/action-obligation records;
- return a small outcome enum/value equivalent to current
  `NOT_APPLICABLE`, `CONTINUE_LOOP`, and `STOP_TURN`;
- preserve exact current no-tool failure reason and deterministic no-action
  answer.

`ToolRepromptContextBudgetHandler` should remain the router for context-budget
fallback order:

1. pending action obligation failure;
2. compact mutation continuation;
3. compact read-only evidence continuation;
4. deterministic context-budget stop.

## Explicit Non-Moves For T538

T538 must not:

- change compact mutation prompts or tool schemas;
- change trace warning codes/details;
- change context-budget fallback ordering;
- move compact read-only evidence continuation;
- move `ToolCallLoop.finalizeAnswer(...)`;
- move normal reprompt result application;
- alter task contract, expected target, or protected-read behavior.

## Why This Is The Correct Next Slice

`ToolRepromptContextBudgetHandler` currently mixes two responsibilities:

- routing the context-budget fallback ladder;
- executing compact mutation continuations.

`CompactMutationContinuationPlanner` already owns frame/tool/control planning.
The missing owner is the executor that applies the plan and classifies the
result. Extracting that executor is a coherent ownership move and has existing
coverage in `ToolRepromptContextBudgetHandlerTest`,
`CompactMutationContinuationPlannerTest`, `ToolMutationEvidenceBudgetGateTest`,
and context-budget scenarios in `ToolCallLoopTest`.

## Rejected Alternatives

### Extract final-answer finalization next

Rejected.

Reason: finalization combines unresolved tool-call suppression, tool-call
stripping, suspicious HTML stripping, protected-content sanitization, and
`LoopResult` assembly. It needs a separate decision packet before code moves.

### Move compact read-only evidence continuation next

Rejected.

Reason: it is already isolated in `CompactReadOnlyEvidenceContinuation`.
Further movement would be mostly internal cleanup unless source inspection
finds a sharper ownership problem.

### Convert remaining direct `state.currentText` writes mechanically

Rejected.

Reason: the remaining direct writes are not all terminal response writes. Some
are continuation setup or final loop fallback. Hiding those behind helpers
would reduce readability.

## Acceptance Criteria

- Inspect post-T536 response-state assignments from fresh beta.
- Classify remaining direct assignments.
- Decide whether the next ticket is implementation or planning.
- Select only one coherent next owner.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

