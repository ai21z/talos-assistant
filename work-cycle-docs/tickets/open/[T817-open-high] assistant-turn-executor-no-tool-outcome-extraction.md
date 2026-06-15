# T817 AssistantTurnExecutor No-Tool Outcome Extraction

Status: open
Priority: high
Wave: 5
Owner: architecture/runtime behavior preservation

## Summary

Extract the no-tool outcome orchestration from
`cli.modes.AssistantTurnExecutor.resolveNoToolAnswer(...)` into package-private
`AssistantNoToolOutcomeResolver`.

T817 is behavior-preserving architecture work. It narrows
`AssistantTurnExecutor` without changing public CLI behavior, public Java
production API, streaming/buffered branch selection, trace lifecycle
ownership, the tool-loop outcome path, or `TurnOutput` assembly.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T817 HEAD:
  `884893d5dd044a44f856855c4bd3758fe8a90ac7`
- Talos version: `0.10.5`
- Guard ticket:
  `work-cycle-docs/tickets/done/[T816-done-high] assistant-turn-executor-no-tool-outcome-characterization.md`
- Guard report:
  `work-cycle-docs/reports/t816-assistant-turn-executor-no-tool-outcome-characterization.md`

The priority index remains review-order evidence only. T817 succeeds by
preserving behavior while moving one ownership boundary, not by reaching a
specific generated score.

## Scope

- Add package-private `AssistantNoToolOutcomeResolver` in
  `dev.talos.cli.modes`.
- Move no-tool outcome orchestration out of
  `AssistantTurnExecutor.resolveNoToolAnswer(...)`:
  - malformed protocol/debris retry ordering;
  - `emptyNoToolLoopResult(...)`;
  - no-tool missing-mutation retry ordering;
  - read-evidence handoff ordering;
  - read-only inspection retry ordering;
  - final call ordering into executor-owned shaping callbacks.
- Keep `AssistantTurnExecutor.resolveNoToolAnswer(...)` as a thin adapter that
  binds executor-owned callbacks.

## Non-Goals

- No public `AssistantTurnExecutor.execute(...)` signature change.
- No public CLI/product behavior change.
- No tool-loop outcome move.
- No trace begin/set/clear move.
- No streaming/buffered branch selection move.
- No direct-answer or unsupported preflight move.
- No `shapeAnswerWithoutTools(...)` move.
- No `shapeAnswerAfterToolLoop(...)` move.
- No `ToolLoopAnswerResolution` move.
- No `TurnOutput` assembly move.
- No package-cycle cleanup.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.

## Callback Surface

`AssistantNoToolOutcomeResolver` uses package-private callback interfaces:

- `RetryPlanResolver` for retry-message `CurrentTurnPlan` resolution;
- `PhaseTransition` for executor-owned verify phase transitions;
- `ToolAnswerShaper` for executor-owned tool-shaped answer rendering;
- `NoToolAnswerShaper` for executor-owned no-tool answer rendering.

This keeps the resolver from pulling broad executor ownership inward while
still moving no-tool outcome ordering out of `AssistantTurnExecutor`.

## Guard Coverage

T816 characterization guards:

- `noToolMalformedMutationDebrisRetryRunsBeforeNoActionShaping`;
- `noToolMissingMutationRetryUsesBufferedPathAndVisibleSummary`;
- `noToolReadEvidenceHandoffRunsBeforeFinalAnswer`;
- `noToolReadOnlyInspectionRetryRunsBeforeFinalAnswerAndBuffersDeflection`;
- `noToolOutcomeReportPinsT817MoveStayBoundary`.

Existing guards:

- `AssistantTurnExecutorDebrisRetryTest`;
- `NoToolGroundingRetryTest`;
- `UnsupportedFinalAnswerTruthfulnessTest`;
- `AssistantTurnExecutorTest`.

## Current Implementation State

The initial T817 extraction has been implemented locally and remains open for
review/closeout. Closeout should verify focused guards, `dev.talos.cli.modes.*`,
full `check`, and `wikiEvidenceCloseGate --rerun-tasks` before moving this
ticket to `done`.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorNoToolOutcomeCharacterizationTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorDebrisRetryTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.UnsupportedFinalAnswerTruthfulnessTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short
```
