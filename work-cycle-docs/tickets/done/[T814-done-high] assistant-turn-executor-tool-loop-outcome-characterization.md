# T814 AssistantTurnExecutor Tool-Loop Outcome Characterization

Status: done
Priority: high
Wave: 5
Owner: architecture/test discipline

## Summary

Characterize the post-tool-loop outcome pipeline in
`cli.modes.AssistantTurnExecutor` before any production extraction.

T814 is not a refactor ticket. It pins the behavior currently coordinated by
`resolveToolLoopAnswer(...)` so T815 can later extract the boundary deliberately
instead of moving retry, evidence recovery, summary, and answer-shaping logic
blindly.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T814 HEAD: `c8e53006556045f4af18c2a407741f36da093305`
- Talos version: `0.10.5`
- Generated architecture evidence: `AssistantTurnExecutor` remains Wave 5 rank
  1 with priority index `384` on the pre-T814 generated report.
- Primary source:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- Characterization report:
  `work-cycle-docs/reports/t814-assistant-turn-executor-tool-loop-outcome-characterization.md`

## Scope

- Add focused characterization tests around post-tool-loop outcome behavior.
- Record current source anchors and the proposed T815 move/stay boundary.
- Update the living evidence wiki so T814 is visible as the active Wave 5 move.

## Non-Goals

- No production extraction.
- No package-cycle cleanup.
- No `LoopResult` or `ToolOutcome` move.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.
- No Wave 5 closeout claim.

## Acceptance Criteria

- `AssistantTurnExecutorToolLoopOutcomeCharacterizationTest` exists.
- Characterization covers:
  - post-tool synthesis retry before outcome shaping;
  - missing-mutation retry after a tool loop;
  - inspect-completeness retry and merged read evidence;
  - approval-denied mutation suppression of missing-mutation retry;
  - report-pinned T815 move/stay boundary.
- `dev.talos.cli.modes.*` remains green.
- `check` remains green.
- `wikiEvidenceCloseGate --rerun-tasks` remains green after wiki updates.
- No production source changes are made by T814.

## T815 Candidate Boundary

If T814 is green and reviewed, T815 may extract a package-private
`AssistantToolLoopOutcomeResolver` in `dev.talos.cli.modes`.

Move later in T815:

- `resolveToolLoopAnswer(...)`;
- `visibleToolLoopSummary(...)`;
- orchestration of post-loop synthesis retry, missing-mutation retry,
  inspect-completeness retry, read-evidence recovery, verify-phase transition,
  and the final `shapeAnswerAfterToolLoop(...)` call.

Keep in `AssistantTurnExecutor` until separately proven movable:

- public `execute(...)`;
- streaming/buffered branch selection;
- `ctx.toolCallLoop().run(...)`;
- trace begin/set/clear;
- no-tool outcome path;
- direct-answer and unsupported preflight;
- `TurnOutput` assembly;
- `ToolCallLoop.LoopResult` and `ToolOutcome` compatibility surfaces.

## Completion State

T814 is complete as a characterization-only ticket.

Evidence recorded before closeout:

- `AssistantTurnExecutorToolLoopOutcomeCharacterizationTest` was added with
  behavioral coverage for post-tool synthesis retry, missing-mutation retry,
  inspect-completeness retry, approval-denied mutation handling, and the T815
  move/stay boundary.
- `dev.talos.cli.modes.*` remained green.
- `check` remained green.
- `wikiEvidenceCloseGate --rerun-tasks` remained green after wiki updates.
- T814 made no production source changes.

T815 is the next Wave 5 extraction ticket. Its candidate owner is
package-private `AssistantToolLoopOutcomeResolver`, and it must keep
`ToolCallLoop.LoopResult`, `ToolOutcome`, no-tool outcome handling,
trace begin/set/clear, branch selection, and `TurnOutput` assembly out of the
move.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorToolLoopOutcomeCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short
```
