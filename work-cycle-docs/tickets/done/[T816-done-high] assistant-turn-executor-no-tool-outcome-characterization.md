# T816 AssistantTurnExecutor No-Tool Outcome Characterization

Status: done
Priority: high
Wave: 5
Owner: architecture/runtime behavior preservation

## Summary

Characterize `cli.modes.AssistantTurnExecutor.resolveNoToolAnswer(...)` before
any production extraction into a future package-private
`AssistantNoToolOutcomeResolver`.

T816 is characterization-only. It pins the no-tool outcome pipeline so T817 can
move ownership deliberately rather than moving retry, handoff, buffering, and
answer-shaping behavior blindly.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T816 HEAD:
  `a1b261b62aaca7b619d6fb18ad032c3e37c9ceec`
- Talos version: `0.10.5`
- Source under characterization:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- Characterization report:
  `work-cycle-docs/reports/t816-assistant-turn-executor-no-tool-outcome-characterization.md`

The priority index remains review-order evidence only. T816 succeeds by
capturing current behavior and move/stay boundaries, not by reaching a specific
generated architecture score.

## Scope

- Add focused characterization tests for the no-tool outcome path.
- Record the no-tool outcome source anchors and future extraction boundary.
- Keep production source unchanged.

T816 characterization targets:

- malformed protocol/debris mutation retry before no-action shaping;
- no-tool missing-mutation retry and visible retry summary;
- read-evidence handoff before final no-tool answer;
- read-only inspection retry before final no-tool answer;
- streaming mutation/evidence turns stay buffered so initial unsupported prose
  is not emitted.

## Non-Goals

- No production extraction.
- No `AssistantNoToolOutcomeResolver` implementation.
- No public `AssistantTurnExecutor.execute(...)` signature change.
- No public CLI/product behavior change.
- No tool-loop outcome move.
- No trace begin/set/clear move.
- No branch-selection move.
- No `shapeAnswerWithoutTools(...)` or `shapeAnswerAfterToolLoop(...)` move.
- No `TurnOutput` assembly move.
- No package-cycle cleanup.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.

## T817 Candidate Boundary

Candidate owner: package-private `AssistantNoToolOutcomeResolver` in
`dev.talos.cli.modes`.

Move later in T817:

- `resolveNoToolAnswer(...)` orchestration;
- `emptyNoToolLoopResult(...)`;
- malformed protocol/debris retry ordering;
- no-tool missing-mutation retry ordering;
- read-evidence handoff ordering;
- read-only inspection retry ordering;
- the point at which no-tool final shaping is invoked.

Keep in `AssistantTurnExecutor` until separately characterized:

- public `execute(...)`;
- streaming/buffered branch selection;
- trace begin/set/clear;
- tool-loop outcome path;
- direct-answer and unsupported preflight;
- `shapeAnswerWithoutTools(...)`;
- `shapeAnswerAfterToolLoop(...)`;
- `TurnOutput` assembly.

## Acceptance Criteria

- `AssistantTurnExecutorNoToolOutcomeCharacterizationTest` exists.
- The T816 report names `AssistantNoToolOutcomeResolver` and the T817
  move/stay boundary.
- The characterization test suite covers all T816 targets listed in scope.
- `dev.talos.cli.modes.*`, full `check`, and
  `wikiEvidenceCloseGate --rerun-tasks` remain green.
- No production source files are changed by T816.

## Completion State

T816 is done. It added characterization-only coverage for the no-tool outcome
boundary around `AssistantTurnExecutor.resolveNoToolAnswer(...)`.

Completion evidence:

- `AssistantTurnExecutorNoToolOutcomeCharacterizationTest` covers malformed
  protocol/debris retry, no-tool missing-mutation retry, read-evidence handoff,
  read-only inspection retry, and stream-sink buffering.
- The T816 report records the future T817 owner as package-private
  `AssistantNoToolOutcomeResolver`.
- T816 made no production source changes and did not authorize extraction.
- Focused characterization, `dev.talos.cli.modes.*`, full `check`, and
  `wikiEvidenceCloseGate --rerun-tasks` were green before closeout.

The next Wave 5 move is T817, a behavior-preserving extraction into
`AssistantNoToolOutcomeResolver`.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorNoToolOutcomeCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorDebrisRetryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short
```
