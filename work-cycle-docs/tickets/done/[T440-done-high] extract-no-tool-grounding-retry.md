# [T440-done-high] Extract No-Tool Grounding Retry

## Status

Done.

## Scope

T440 extracts the non-streaming no-tool grounding retry side effect from
`AssistantTurnExecutor` into `NoToolGroundingRetry`.

This is an ownership refactor. It preserves runtime behavior and does not
change streaming grounding annotation, read-only inspection retry,
inspect-completeness retry, missing-mutation retry, or outcome warning
construction.

## Change

Added:

```text
dev.talos.cli.modes.NoToolGroundingRetry
```

`NoToolGroundingRetry` now owns:

- the long no-tool evidence-request retry gate;
- the direct-answer-only/small-talk bypass;
- latest-user-request selection for this retry;
- the corrective grounding retry prompt;
- message append order;
- one supplied model retry call;
- retry text replacement;
- fallback ungrounded annotation.

`AssistantTurnExecutor` keeps the package-visible compatibility wrappers and
delegates through a supplied chat function so the model call still flows through
the existing executor path.

## Guardrails

Preserved:

- exact corrective prompt wording;
- `UNGROUNDED_MIN_CHARS` behavior;
- direct-answer-only and small-talk bypass behavior;
- evidence-request detection via `NoToolAnswerTruthfulnessGuard`;
- assistant-then-user retry message append order;
- retry text replacement behavior;
- blank/identical/exception retry fallback annotation behavior;
- no tool-loop re-entry on the grounding retry path.

Not changed:

- streaming grounding annotation;
- streaming no-tool mutation replacement;
- negative local access correction;
- read-only inspection retry;
- inspect-completeness retry;
- missing-mutation retry;
- outcome warning construction.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable NoToolGroundingRetry
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --no-daemon
```

Wider focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuardTest" --no-daemon
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$GroundingRetryTests' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$StreamingGroundingTests' --no-daemon
```

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T440 retry/orchestration shape before choosing T441. Do not
automatically move inspect-completeness retry or missing-mutation retry without
rechecking current source responsibilities.
