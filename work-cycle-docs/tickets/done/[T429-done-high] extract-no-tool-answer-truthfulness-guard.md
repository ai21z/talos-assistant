# [T429-done-high] Extract No-Tool Answer Truthfulness Guard

## Status

Done.

## Scope

T429 implements the T428 decision:

```text
[T429] Extract no-tool answer truthfulness guard
```

This ticket moves only the pure no-tool answer-truthfulness predicates and
rendering into:

```text
dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard
```

## What Changed

`NoToolAnswerTruthfulnessGuard` now owns:

- malformed no-tool protocol replacement text;
- local workspace access capability correction text;
- ungrounded no-tool annotation text;
- streaming no-tool mutation replacement and annotation text;
- negative local access claim detection;
- evidence-request marker detection;
- streaming no-tool mutation narrative detection;
- streaming no-tool truthfulness enforcement.

`ExecutionOutcome.fromNoTool(...)` now calls the runtime guard directly for the
pure no-tool answer-shaping branches.

`AssistantTurnExecutor` keeps compatibility constants and package-private
wrappers for existing tests and local call sites, but those wrappers delegate to
`NoToolAnswerTruthfulnessGuard`.

## What Did Not Change

This ticket intentionally did not move or change:

- non-streaming no-tool grounding retry orchestration;
- LLM retry prompts or `chatFull(...)` behavior;
- message-list mutation during grounding retry;
- static-web answer overrides;
- inspect-under-completion annotation;
- unsupported-document answer correction;
- protected-read answer guards;
- mutation-failure rendering;
- outcome dominance policy;
- warning construction.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuardTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: variable NoToolAnswerTruthfulnessGuard
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuardTest" `
  --tests "dev.talos.cli.modes.ExecutionOutcomeTest" `
  --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --no-daemon
```

Passed after adding the runtime-owned guard and routing `ExecutionOutcome`
through it.

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T429 integrates cleanly, inspect the remaining answer-shaping surface
again before starting T430.

Do not move static-web answer overrides or inspect-under-completion behavior
without a fresh source inspection.
