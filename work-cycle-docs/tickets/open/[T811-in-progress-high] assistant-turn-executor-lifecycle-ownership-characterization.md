# T811 - AssistantTurnExecutor Lifecycle Ownership Characterization

Status: in-progress
Severity: high
Release gate: no - first Wave 5 refactor entry ticket
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-14
Owner: Codex

## Problem

Wave 5 must not start by moving classes until package cycles look cleaner.
The architecture intelligence report ranks
`dev.talos.cli.modes.AssistantTurnExecutor` as the first review candidate, but
that priority index is an ordering heuristic, not ground truth.

`AssistantTurnExecutor` currently sits at the intersection of turn lifecycle,
approval/tool ownership, trace/privacy behavior, retry behavior, and high
method/class traffic. A careless extraction can create stale turn state,
approval-memory bleed, trace bleed, privacy leakage, or false-success behavior.

## Required Behavior

1. Characterize the current `AssistantTurnExecutor` behavior before extracting
   or moving ownership.
2. Treat lifecycle ownership as the primary invariant:
   - what is application-scoped;
   - what is session-scoped;
   - what is turn-scoped;
   - what is tool-loop/tool-call-scoped;
   - what is trace-scoped;
   - what is temporary.
3. Use the T807 priority index only to choose where to inspect first.
4. Let characterization tests and source evidence decide what can be extracted.
5. Identify responsibilities that belong outside `AssistantTurnExecutor`
   without changing public CLI/product behavior.
6. Keep diff pressure away from unrelated setup/profile code such as
   `SetupCmd.java`.
7. Preserve approval, trace, prompt-debug, verification, retry, and final-answer
   semantics unless a later ticket explicitly changes them.

## Non-Goals

- No broad package move.
- No behavior-changing rewrite.
- No public CLI/product API change.
- No Qodana task, config, version, mode, or evidence change.
- No CodeQL, JFR, Error Prone, NullAway, or JSpecify integration.
- No release candidate recut.
- No personal PTY or Wave 3 reopening.

## Architecture Metadata

- Capability: Wave 5 lifecycle ownership characterization and first
  behavior-preserving extraction.
- Operation(s): source inspection, characterization tests, focused refactor
  planning, and small behavior-preserving turn-preparation extraction.
- Owning package/class:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`.
- Related evidence:
  `build/reports/talos/architecture-intelligence/current/11-wave5-ticket-sequence.md`
  and
  `build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json`.
- Risk and approval: high, because this class touches approval/tool ownership,
  trace/privacy behavior, and turn lifecycle.
- Protected path behavior: must preserve existing protected-path and privacy
  handling; no new protected reads or artifact writes.
- Checkpoint behavior: must preserve existing mutation/checkpoint semantics.
- Evidence obligation: every extracted responsibility needs a characterization
  test or a cited existing test proving unchanged behavior.
- Verification profile: focused tests first, then `check`, then
  `wikiEvidenceCloseGate` if wiki/report claims are updated.
- Allowed refactor scope: `AssistantTurnExecutor` and directly related
  extracted collaborators only, with no unrelated cleanup.

## Acceptance Criteria

- Current behavior around task execution, approval/tool handoff, retry,
  trace/prompt-debug, verification, and final answer shaping is characterized
  by tests or cited existing tests.
- A lifecycle ownership map for the class is recorded in the ticket or an
  associated report before extraction.
- Any extraction is behavior-preserving and backed by focused tests.
- The T807 score is referenced only as a review-order heuristic.
- No unrelated `SetupCmd.java` or setup/profile branch conflict surface is
  touched.
- `.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon` passes when
  implementation begins.
- `.\gradlew.bat check --no-daemon` passes before closure.

## Verification

Initial planning/characterization commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.*" --no-daemon
.\gradlew.bat check --no-daemon
```

If wiki/report claims are updated during this ticket:

```powershell
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Current Evidence

- `build/reports/talos/architecture-intelligence/current/11-wave5-ticket-sequence.md`
  ranks `cli.modes.AssistantTurnExecutor` first.
- `build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json`
  currently records `cli.modes.AssistantTurnExecutor` first on this commit with
  priority index `401`, hotspot `290`, lifecycle `56`, approval/tool `30`,
  trace/privacy `25`, and confidence `INFERRED_REVIEW`.
- The previous `466` / hotspot `355` value was pre-extraction evidence. It is
  useful as before/after signal, not a stable requirement.
- The score is an unnormalized priority index for ordering review work, not a
  grade, proof of ownership correctness, or extraction mandate.
- `src/main/java/dev/talos/cli/modes/AssistantTurnPreparation.java` contains
  the first extracted ordered turn-preparation collaborator. Current generated
  evidence lists it as a point-in-time candidate with priority index `136`.
- `work-cycle-docs/reports/t811-assistant-turn-executor-lifecycle-characterization.md`
  records the first Wave 5 lifecycle ownership map, source anchors, existing
  behavior coverage, extraction order, stop conditions, and post-extraction
  result.
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorLifecycleCharacterizationTest.java`
  pins stable report sections and the public `AssistantTurnExecutor.execute(...)`
  API shape. It intentionally does not pin internal helper call sites or
  transient status sentences.
