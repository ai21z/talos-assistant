# [T548-done-high] Extract Tool Loop Result Summary Formatter

Status: done
Priority: high
Date: 2026-05-27
Branch: `T548`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `9c04ca9e`
Predecessor: `T547`

## Scope

T548 extracts loop-result summary formatting out of
`ToolCallLoop.LoopResult` while preserving the existing public
`LoopResult.summary()` compatibility method.

It intentionally does not move `ToolCallLoop.LoopResult`,
`ToolCallLoop.ToolOutcome`, final-answer rendering, mutation outcome rendering,
failure policy, retry policy, or any user-visible wording.

## Changes

- Added `dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatter`.
- Moved summary-string construction into the formatter.
- Moved recovered edit-failure display suppression into the formatter.
- Moved summary path normalization into the formatter.
- Kept `ToolCallLoop.LoopResult.summary()` as a wrapper that delegates to the
  formatter.
- Added focused behavior and ownership tests.

## Preserved Wording

The following summary fragments are unchanged:

- `[Used N tool(s): ... | M iteration(s)]`
- `[N failed]`
- `[iteration limit reached]`
- `[failure policy stopped]`

Recovered edit failures are still suppressed from the displayed failed-call
count when a later successful mutating outcome targets the same normalized path.

## TDD Evidence

RED command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --no-daemon
```

RED result:

```text
ToolLoopResultSummaryFormatterTest.java: cannot find symbol
symbol: variable ToolLoopResultSummaryFormatter
```

Failure reason: the test referenced the intended summary formatter owner before
the class existed.

GREEN command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --no-daemon
```

GREEN result: passed.

Focused regression command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Focused regression result: passed.

## Ownership Decision

`ToolLoopResultSummaryFormatter` belongs to `dev.talos.runtime.toolcall`.

Reason:

- the summary is tool-loop telemetry, not final-answer generation;
- it depends on `LoopResult` counters and `ToolOutcome` failure-shape facts;
- keeping the public `LoopResult.summary()` method avoids broad API churn;
- the extraction makes `LoopResult` closer to a compatibility transport value.

## Rejected Scope

### Move `LoopResult`

Rejected.

It is still the public return type of `ToolCallLoop.run(...)` and has broad
CLI, runtime, test, and E2E consumers.

### Move `ToolOutcome`

Rejected.

It still has broad consumers and should not move as a mechanical follow-up.

### Change summary wording

Rejected.

This ticket is an ownership extraction only. Wording and behavior must remain
exactly compatible.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T548 merges, inspect the remaining `ToolCallLoop` nested value shape.
Do not move `LoopResult` or `ToolOutcome` unless source inspection proves the
records have become plain enough to justify a compatibility migration.
