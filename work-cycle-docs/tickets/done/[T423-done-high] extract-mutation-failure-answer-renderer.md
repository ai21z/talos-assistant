# [T423-done-high] Extract Mutation Failure Answer Renderer

## Status

Done.

## Scope

T423 implements the T422 decision:

```text
[T423] Extract mutation failure answer renderer
```

The target owner is:

```text
dev.talos.runtime.outcome.MutationFailureAnswerRenderer
```

This ticket moves mutation-failure final-answer rendering out of
`AssistantTurnExecutor` without changing wording, status classification, warning
types, warning order, verification behavior, or runtime behavior.

## What Changed

Added `MutationFailureAnswerRenderer`.

It now owns:

- false mutation-claim annotation;
- partial mutation outcome summary;
- denied mutation summary;
- read-only denied mutation summary;
- invalid mutation summary;
- mutation-claim phrase detection;
- mutation failure message trimming;
- read-only denied answer cleanup.

`ExecutionOutcome` now calls `MutationFailureAnswerRenderer` directly for those
mutation-failure answer-shaping steps.

`AssistantTurnExecutor` keeps compatibility constants and package-private test
wrappers, but those wrappers delegate to the runtime outcome owner.

## What Did Not Change

This ticket intentionally did not move:

- denied protected-read summaries;
- protected-read answer guards;
- unsupported document claim correction;
- static-web import grounding;
- read-only web diagnostics;
- selector search or selector mismatch grounding;
- no-tool local-access correction;
- streaming no-tool truthfulness;
- non-streaming grounding retry;
- inspect-under-completion annotation;
- dominance policy;
- warning construction;
- trace recording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: variable MutationFailureAnswerRenderer
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" --no-daemon
```

Passed after adding the renderer.

## Focused Verification

Passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.MutationFailureAnswerRendererTest" `
  --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --tests "dev.talos.cli.modes.ExecutionOutcomeTest" `
  --no-daemon
```

Note: an earlier parallel focused test attempt failed with a Gradle
`Unable to delete directory ... build\test-results\test\binary` error because
multiple Gradle test processes were launched against the same worktree. The
same coverage passed when rerun serially. That was a test-runner concurrency
mistake, not a code failure.

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T423 integrates cleanly, inspect the remaining `AssistantTurnExecutor`
answer-shaping responsibilities before choosing T424.

Do not assume the next ticket is another extraction.

Likely inspection areas:

- protected-read denial summary ownership;
- unsupported document answer correction ownership;
- static-web grounding ownership;
- no-tool and streaming truthfulness ownership.
