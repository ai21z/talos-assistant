# [T434-done-high] Extract Post-Tool Synthesis Retry

## Status

Done.

## Scope

T434 implements the T433 decision:

```text
[T434] Extract post-tool synthesis retry
```

This ticket moves only post-tool deflection detection and one-shot synthesis
retry orchestration into:

```text
dev.talos.cli.modes.PostToolSynthesisRetry
```

## What Changed

`PostToolSynthesisRetry` now owns:

- post-tool deflection marker detection;
- capability-recitation deflection detection;
- original-request anchoring for the retry prompt;
- retry prompt construction;
- appending the assistant deflection and corrective user retry prompt;
- calling a supplied chat function and accepting only substantive retry text.

`AssistantTurnExecutor` keeps compatibility wrappers for:

- `isDeflection(...)`;
- `synthesisRetryIfNeeded(...)`.

Those wrappers delegate to `PostToolSynthesisRetry`.

## Why This Owner

The new owner remains in CLI mode ownership because it is retry orchestration,
not runtime outcome rendering. It mutates turn messages and calls the model
through a supplied chat function. It does not call `ctx.llm()` directly, so
provider controls and tool-surface selection remain owned by the existing
`AssistantTurnExecutor.chatFull(...)` path.

## What Did Not Change

This ticket intentionally did not move or change:

- missing-mutation retry;
- read-only inspection retry;
- inspect-completeness retry;
- no-tool grounding retry;
- `chatFull(...)` provider-control construction;
- tool-surface narrowing;
- tool-loop re-entry;
- static-web answer overrides;
- outcome dominance policy;
- retry prompt wording;
- message append order;
- final answer wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.PostToolSynthesisRetryTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: variable PostToolSynthesisRetry
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.PostToolSynthesisRetryTest" --no-daemon
```

Passed after adding `PostToolSynthesisRetry`.

Focused regression coverage also passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.PostToolSynthesisRetryTest" `
  --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --tests "dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest" `
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

After T434 integrates cleanly, inspect the remaining retry orchestration lane
again before choosing T435.

Do not extract mutation retry, inspection retry, or no-tool grounding retry
without a fresh source inspection and a narrower owner decision.
