# [T427-done-high] Extract Unsupported Document Answer Guard

## Status

Done.

## Scope

T427 implements the T426 decision:

```text
[T427] Extract unsupported document answer guard
```

This ticket moves unsupported-document answer correction out of
`AssistantTurnExecutor` and into:

```text
dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard
```

## What Changed

`UnsupportedDocumentAnswerGuard` now owns:

- unsupported document capability notes;
- removal of unsupported binary-document content claims;
- unsupported grep/search no-match correction;
- supported text-file read exemptions while unsupported document reads are
  present.

`ExecutionOutcome` now calls `UnsupportedDocumentAnswerGuard` directly for this
answer-shaping step.

`AssistantTurnExecutor` keeps a package-private compatibility wrapper for
existing call sites and tests, but the implementation delegates to
`UnsupportedDocumentAnswerGuard`.

## What Did Not Change

This ticket intentionally did not move or change:

- unsupported-document preflight behavior;
- unsupported-document mutation policy;
- document extraction capability configuration;
- static-web answer overrides;
- no-tool and streaming truthfulness behavior;
- inspect-under-completion behavior;
- protected-read answer guards;
- mutation-failure rendering;
- outcome dominance policy;
- final warning wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuardTest" --no-daemon
```

Expected failure after test-only corrections:

```text
cannot find symbol: variable UnsupportedDocumentAnswerGuard
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuardTest" `
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

After T427 integrates cleanly, inspect remaining `AssistantTurnExecutor`
answer-shaping responsibilities again before starting T428.

Do not move static-web diagnostic overrides, no-tool/streaming truthfulness, or
inspect-under-completion behavior without a fresh boundary decision.
