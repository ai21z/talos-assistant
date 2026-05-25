# [T431-done-high] Extract Inspect Under-Completion Answer Guard

## Status

Done.

## Scope

T431 implements the T430 decision:

```text
[T431] Extract inspect under-completion answer guard
```

This ticket moves only the pure inspect under-completion final-answer
annotation logic into:

```text
dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard
```

## What Changed

`InspectUnderCompletionAnswerGuard` now owns:

- inspect under-completion minimum answer length;
- inspect under-completion annotation text;
- inspect-first request marker detection;
- read-only tool invocation counting;
- final-answer annotation for the "multi-file inspection requested, at most
  one read-only tool used" shape.

`ExecutionOutcome.fromToolLoop(...)` now calls the runtime outcome guard
directly.

`AssistantTurnExecutor` keeps compatibility constants and package-private
wrappers for existing tests and local call sites, but those wrappers delegate to
`InspectUnderCompletionAnswerGuard`.

## What Did Not Change

This ticket intentionally did not move or change:

- inspect-completeness retry orchestration;
- missing primary-file read detection;
- linked-script evidence detection;
- static-web answer overrides;
- no-tool grounding retry;
- mutation-failure answer rendering;
- protected-read answer guards;
- unsupported-document answer correction;
- outcome dominance policy;
- warning construction;
- user-visible annotation wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuardTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: variable InspectUnderCompletionAnswerGuard
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuardTest" --no-daemon
```

Passed after adding the runtime-owned guard.

Focused regression coverage also passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --tests "dev.talos.cli.modes.ExecutionOutcomeTest" `
  --tests "dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuardTest" `
  --no-daemon
```

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T431 integrates cleanly, inspect the remaining answer-shaping surface
again before choosing T432.

Do not move inspect-completeness retry, static-web answer overrides, or
no-tool grounding retry without a fresh source inspection.
