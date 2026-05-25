# [T446-done-high] Extract Exact-Write Context Fallback

## Status

Done.

## Scope

T446 implements the T445 decision: extract only the current-turn exact-write
context-budget fallback from `AssistantTurnExecutor`.

This is an ownership refactor. It preserves runtime behavior and does not
change `ToolCallRepromptStage`, compact mutation continuation, compact
read-only evidence continuation, missing-mutation retry, context-budget skipped
retry wording, static repair behavior, final answer wording, or outcome
dominance.

## Change

Added:

```text
dev.talos.cli.modes.ExactWriteContextFallback
```

`ExactWriteContextFallback` now owns:

- exact-literal fallback eligibility;
- write-file-only compact fallback tool narrowing;
- compact fallback plan construction;
- compact fallback message construction;
- fallback debug-tag attachment;
- `CONTEXT_BUDGET_CURRENT_TURN_FALLBACK` trace recording.

`AssistantTurnExecutor` keeps lifecycle placement:

- catch `EngineException.ContextBudgetExceeded` around the initial backend
  call;
- ask `ExactWriteContextFallback` whether a compact request exists;
- call the existing streaming or non-streaming backend path with that compact
  request;
- rethrow the original context-budget failure when no fallback applies.

## Guardrails

Preserved:

- exact compact prompt wording;
- exact fallback eligibility;
- no fallback for deictic/non-literal mutation requests;
- stale-history omission;
- stream-sink presence still takes the buffered mutation path because mutation
  turns do not use visible streaming;
- `talos.write_file`-only fallback tool surface;
- required-tool provider controls through the existing control path;
- `context-budget-current-turn-fallback` debug tag;
- `RETRIED_COMPACT_CONTEXT` trace status;
- `CONTEXT_BUDGET_CURRENT_TURN_FALLBACK` warning code;
- streaming and non-streaming fallback behavior.

Not changed:

- `MissingMutationRetry`;
- `ToolCallRepromptStage`;
- compact mutation continuation;
- compact read-only evidence continuation;
- `ResponseObligationVerifier` context-budget wording;
- final answer wording;
- static repair behavior;
- outcome dominance.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ExactWriteContextFallbackTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable ExactWriteContextFallback
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ExactWriteContextFallbackTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation" --no-daemon
```

Adjacent compact-continuation verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant" --no-daemon
```

## Full Verification

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T446 is integrated, inspect the post-extraction context-budget
continuation shape before choosing T447.

Do not move `ToolCallRepromptStage` compact mutation or compact read-only
evidence continuations without a fresh boundary decision. They are loop-state
continuations, not current-turn initial-call fallbacks.
