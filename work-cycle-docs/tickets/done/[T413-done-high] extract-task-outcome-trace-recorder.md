# [T413-done-high] Extract Task Outcome Trace Recorder

## Status

Done.

## Scope

T413 implements the boundary selected by T412:

```text
dev.talos.runtime.trace.TaskOutcomeTraceRecorder
```

The ticket extracts only structured task-outcome trace recording from
`ExecutionOutcome`. It does not change final answer wording, dominance policy,
static-verification rendering, protected-read safety, evidence containment,
embedded verification parsing, or read-only tool-loop-limit handling.

## What Changed

`TaskOutcomeTraceRecorder` now owns the trace adapter logic for:

- recording `TaskVerificationResult` into `LocalTurnTraceCapture`;
- recording each `TaskOutcome` warning into `LocalTurnTraceCapture`;
- recording final outcome summary fields into `LocalTurnTraceCapture`;
- deriving trace-facing approval status from `TaskOutcome`.

`ExecutionOutcome` still owns:

- when task-outcome trace recording happens;
- final answer shaping;
- outcome dominance;
- task-outcome assembly;
- protocol-sanitized trace events for malformed protocol or read-only denied
  mutation cases.

The recorder accepts completion and verification statuses as strings so it does
not depend on `ExecutionOutcome.CompletionStatus` or
`ExecutionOutcome.VerificationStatus`.

## Behavior Preservation

The extraction preserves the previous trace behavior:

- verification status, summary, and problems are still recorded;
- all truth warnings are still recorded with the same type names and messages;
- outcome status, verification status, approval status, mutation status, and
  task completion classification are still recorded with the same strings;
- approval status remains:
  - `DENIED` for denied tool outcomes or denied mutation outcomes;
  - `GRANTED_OR_NOT_REQUIRED` when mutation success count is positive;
  - `NONE` when no mutation or denial exists;
  - `UNKNOWN` only for null task/mutation outcome input.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.TaskOutcomeTraceRecorderTest" --no-daemon
```

failed at compile time because `TaskOutcomeTraceRecorder` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.TaskOutcomeTraceRecorderTest" --no-daemon
```

passed after adding the recorder.

Focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.TaskOutcomeTraceRecorderTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

passed after wiring `ExecutionOutcome` to the recorder.

## Required Gate

Before integration, run:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `git diff --check`: passed, with the expected line-ending warning for
  `ExecutionOutcome.java`.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T413 integrates cleanly, inspect the post-T413 `ExecutionOutcome` shape
before choosing T414. Do not assume the next extraction is automatic; the
remaining candidates still mix dominance, evidence adaptation, embedded
verification fallback, compatibility answer shaping, and read-only limit
rendering.
