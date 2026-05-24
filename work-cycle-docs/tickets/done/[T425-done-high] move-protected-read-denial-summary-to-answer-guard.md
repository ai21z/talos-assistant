# [T425-done-high] Move Protected Read Denial Summary To Answer Guard

## Status

Done.

## Scope

T425 implements the T424 decision:

```text
[T425] Move protected read denial summary to protected read answer guard
```

This ticket moves only denied protected-read answer rendering out of
`AssistantTurnExecutor` and into:

```text
dev.talos.runtime.outcome.ProtectedReadAnswerGuard
```

## What Changed

`ProtectedReadAnswerGuard` now owns:

- detecting denied `talos.read_file` protected-read outcomes;
- rendering the protected-read denial replacement answer;
- canonicalizing protected-read denial display paths.

`ExecutionOutcome` now calls `ProtectedReadAnswerGuard` directly for denied
protected-read answer shaping.

`AssistantTurnExecutor` keeps a package-private compatibility wrapper for
existing tests and local call sites, but the wrapper delegates to
`ProtectedReadAnswerGuard`.

## What Did Not Change

This ticket intentionally did not move or change:

- approved protected-read postcondition behavior;
- protected-history suppression behavior;
- protected path classification;
- approved protected-read evidence rendering;
- unsupported document claim correction;
- static-web grounding;
- no-tool grounding retry;
- streaming no-tool truthfulness;
- inspect-under-completion annotation;
- outcome dominance policy;
- warning construction.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ProtectedReadAnswerGuardTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: method summarizeDeniedProtectedReadOutcomesIfNeeded(String,LoopResult)
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ProtectedReadAnswerGuardTest" `
  --tests "dev.talos.cli.modes.ExecutionOutcomeTest" `
  --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --no-daemon
```

Passed after adding the runtime-owned summary method and wiring
`ExecutionOutcome`.

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T425 integrates cleanly, inspect the remaining `AssistantTurnExecutor`
answer-shaping responsibilities again.

Do not jump straight into a large static-web or no-tool extraction.

The likely remaining lanes are:

- unsupported document answer correction;
- static-web answer grounding;
- no-tool and streaming truthfulness;
- inspect-under-completion annotation.
