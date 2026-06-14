# T815 AssistantTurnExecutor Tool-Loop Outcome Extraction

Status: done
Priority: high
Wave: 5
Owner: architecture/runtime behavior preservation

## Summary

Extract the post-tool-loop outcome orchestration from
`cli.modes.AssistantTurnExecutor.resolveToolLoopAnswer(...)` into a
package-private `AssistantToolLoopOutcomeResolver`.

T815 is behavior-preserving architecture work. It narrows
`AssistantTurnExecutor` without changing public CLI behavior, public Java
production API, tool-loop execution ownership, trace lifecycle ownership, or
the no-tool outcome path.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T815 HEAD:
  `06f47bae3bcb6699b572d82f3c4061be773cbd47`
- Talos version: `0.10.5`
- Guard ticket:
  `work-cycle-docs/tickets/done/[T814-done-high] assistant-turn-executor-tool-loop-outcome-characterization.md`
- Guard report:
  `work-cycle-docs/reports/t814-assistant-turn-executor-tool-loop-outcome-characterization.md`

The priority index remains review-order evidence only. T815 succeeds by
preserving behavior while moving one ownership boundary, not by reaching a
specific generated score.

## Scope

- Add package-private `AssistantToolLoopOutcomeResolver` in
  `dev.talos.cli.modes`.
- Move post-tool-loop outcome orchestration out of
  `AssistantTurnExecutor.resolveToolLoopAnswer(...)`:
  - post-tool synthesis retry;
  - missing-mutation retry;
  - inspect-completeness retry;
  - retry evidence merge;
  - read-evidence recovery;
  - visible summary composition;
  - verify phase transition callback ordering;
  - final answer-shaping callback ordering.
- Keep `AssistantTurnExecutor.resolveToolLoopAnswer(...)` as a thin adapter
  that binds executor-owned callbacks.

## Non-Goals

- No public `AssistantTurnExecutor.execute(...)` signature change.
- No public CLI/product behavior change.
- No `ToolCallLoop.LoopResult` move.
- No `ToolOutcome` move.
- No no-tool outcome extraction.
- No trace begin/set/clear move.
- No streaming/buffered branch selection move.
- No `TurnOutput` assembly move.
- No package-cycle cleanup.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.

## Move/Stay Boundary

Moved to `AssistantToolLoopOutcomeResolver`:

- `PostToolSynthesisRetry.synthesizeIfNeeded(...)` ordering.
- `MissingMutationRetry.retryIfNeeded(...)` ordering and retry evidence merge.
- `InspectCompletenessRetry.retryIfNeeded(...)` ordering and retry evidence
  selection.
- `ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(...)`
  ordering.
- Visible tool-loop summary composition.
- The point at which verify-phase transition and final answer shaping are
  invoked.

Stayed in `AssistantTurnExecutor`:

- public `execute(...)`;
- streaming/buffered dispatch branch selection;
- `ctx.toolCallLoop().run(...)`;
- trace begin/set/clear;
- no-tool outcome path;
- direct-answer and unsupported preflight;
- retry-message `CurrentTurnPlan` resolution;
- `moveToVerifyAfterSuccessfulMutation(...)` implementation;
- `shapeAnswerAfterToolLoop(...)` implementation;
- `TurnOutput` assembly.

## Callback Surface

`AssistantToolLoopOutcomeResolver` uses package-private callback interfaces:

- `RetryPlanResolver` for retry-message `CurrentTurnPlan` resolution;
- `PhaseTransition` for executor-owned verify phase transitions;
- `AnswerShaper` for executor-owned answer shaping.

This keeps the resolver from pulling broad executor ownership inward while
still moving the post-loop ordering out of `AssistantTurnExecutor`.

## Guard Coverage

T814 characterization guards:

- `postToolLoopSynthesisRetryRunsBeforeOutcomeShaping`;
- `postToolLoopMissingMutationRetryUsesRetryLoopEvidenceForFinalAnswer`;
- `postToolLoopInspectCompletenessRetryMergesRetryReadEvidenceAndSingleSummary`;
- `postToolLoopDeniedMutationDoesNotTriggerMissingMutationRetry`;
- `toolLoopOutcomeReportPinsMoveStayBoundary`.

Existing guards named by review:

- `AssistantTurnExecutorPhasePolicyTest` covers mutation success moving the
  execution phase to `VERIFY`.
- `AssistantTurnExecutorTest.partialMultiTargetReadRunsEvidenceRecoveryForAllTargets`
  covers partial-target read evidence recovery.
- `ReadEvidenceHandoffTest.pathExistenceRecoveryRunsAfterIrrelevantReadEvidence`
  covers helper-level read evidence recovery.

## Completion State

T815 is done. It extracted post-tool-loop outcome orchestration into
package-private `AssistantToolLoopOutcomeResolver` while preserving behavior.

`AssistantTurnExecutor.resolveToolLoopAnswer(...)` is now a thin adapter that
binds executor-owned callbacks. The extraction preserved:

- no-tool outcome handling in `AssistantTurnExecutor`;
- trace begin/set/clear ownership in `AssistantTurnExecutor`;
- streaming/buffered branch selection in `AssistantTurnExecutor`;
- `ToolCallLoop.LoopResult` and `ToolOutcome` compatibility surfaces;
- `TurnOutput` assembly in `AssistantTurnExecutor`.

T815 did not complete Wave 5. The next Wave 5 move is T816, a
characterization-only ticket for the no-tool outcome boundary before any
future `AssistantNoToolOutcomeResolver` extraction.

Closeout verification recorded green focused guards, `dev.talos.cli.modes.*`,
full `check`, and `wikiEvidenceCloseGate --rerun-tasks`.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorToolLoopOutcomeCharacterizationTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorPhasePolicyTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadEvidenceHandoffTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short
```
