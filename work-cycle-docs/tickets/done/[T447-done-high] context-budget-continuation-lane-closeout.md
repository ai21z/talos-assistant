# [T447-done-high] Context-Budget Continuation Lane Closeout

## Status

Done.

## Scope

T447 reinspects the post-T446 context-budget continuation shape after
`ExactWriteContextFallback` was extracted from `AssistantTurnExecutor`.

This is a no-code closeout and decision ticket. It does not change runtime
behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `db9792c1`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 3470 lines |
| `ExactWriteContextFallback.java` | 168 lines |
| `ToolCallRepromptStage.java` | 2730 lines |
| Architecture baseline | 0 |

## Post-T446 Source Shape

T446 successfully split the current-turn exact-write fallback from the main
CLI executor:

- `ExactWriteContextFallback` owns exact-literal eligibility, compact current
  turn prompt construction, write-file-only tool narrowing, fallback debug-tag
  attachment, and `CONTEXT_BUDGET_CURRENT_TURN_FALLBACK` trace recording.
- `AssistantTurnExecutor` keeps the lifecycle placement: catch initial
  `EngineException.ContextBudgetExceeded`, ask the fallback owner whether a
  compact exact-write request exists, call the existing streaming or buffered
  backend path with that compact request, and rethrow the original failure when
  the fallback is not applicable.
- `ToolCallRepromptStage` was intentionally not moved by T446.

The remaining context-budget continuation surface is no longer one lane. It is
two separate runtime tool-loop paths:

| Area | Source | Finding |
|---|---|---|
| Compact mutation continuation | `ToolCallRepromptStage.tryCompactMutationContinuation(...)`, `compactMutationContinuationForContextBudget(...)`, `compactMutationContinuationMessages(...)` | Still stateful loop control. It depends on `LoopState`, pending action obligations, mutation/read-only counters, readback freshness, static repair context, source-derived evidence, sensitive-path filtering, trace events, failure dominance, and whether the tool loop should continue. |
| Compact read-only evidence continuation | `ToolCallRepromptStage.tryCompactReadOnlyEvidenceContinuation(...)`, `readOnlyEvidenceAnswerForCompactFallback(...)`, `readOnlyEvidenceAnswerMessages(...)` | Smaller coherent seam. It owns evidence-only readback selection and compact answer synthesis after a read-only continuation exceeds context, while preserving terminal loop-state behavior. |

`ResponseObligationVerifier.contextBudgetRetrySkippedDetail(...)` and
`deterministicContextBudgetRetrySkippedAnswer(...)` remain small runtime
wording helpers. They are not continuation owners and should not move in this
lane.

## Decision

Close the current-turn exact-write context-budget fallback lane.

Do not extract compact mutation continuation next. It remains too entangled
with loop progression and mutation-obligation state to move safely as a small
hygiene ticket.

The next coherent implementation ticket is:

```text
[T448] Extract compact read-only evidence continuation
```

Target owner:

```text
dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuation
```

The owner should stay in runtime/toolcall ownership because it works with
`LoopState`, calls the runtime LLM continuation, rejects accidental tool calls,
and writes terminal loop state. It should not move into CLI mode ownership or
runtime outcome wording.

## T448 Guardrails

T448 should move only:

- read-only evidence continuation eligibility;
- readback selection for the single required read-only target;
- compact read-only evidence answer message construction;
- compact answer LLM call;
- rejection when the compact answer emits tool calls;
- terminal `LoopState.currentText` / `currentNativeCalls` updates for this
  specific read-only evidence continuation.

T448 must preserve:

- `READ_ONLY_EVIDENCE_COMPACT_CONTINUATION` trace warning behavior;
- `READ_ONLY_EVIDENCE_COMPACT_REJECTED` rejection behavior;
- context-budget failure dominance when compact answer synthesis cannot produce
  a safe answer;
- exact read-only evidence prompt wording;
- no-tool-call compact answer contract;
- single-target readback selection;
- current read-only review/proposal eligibility.

T448 must not change:

- compact mutation continuation;
- exact-write context fallback;
- missing-mutation retry;
- static repair behavior;
- action-obligation failure wording;
- `ResponseObligationVerifier` context-budget wording;
- final answer wording outside the read-only evidence compact continuation.

## Proposed T448 Verification

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceUsesRequestedTargetReadback" --no-daemon
```

Adjacent context-budget checks:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ExactWriteContextFallbackTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --no-daemon
```

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
