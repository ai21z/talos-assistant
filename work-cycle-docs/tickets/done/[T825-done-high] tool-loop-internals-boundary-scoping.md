# T825 Tool-Loop Internals Boundary Scoping

Status: done
Priority: high
Wave: 5
Owner: architecture/runtime tool-call loop

## Summary

Scoped the remaining `runtime.toolcall` internals before choosing a T826
production seam.

T825 was characterization/scoping only. It followed T824, which extracted
`ToolCallLoop` orchestration into package-private
`dev.talos.runtime.ToolCallLoopEngine` while keeping `ToolCallLoop` as the
public facade.

T825 did not authorize production extraction.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- T824 implementation commit:
  `2d4a9611ad7357cb50f080d5b9c468a5a824f06e`
- T824 closeout commit:
  `85d53c372f2c6a76aea1be9b04aa212489cfe5c7`
- T825 scoping commit:
  `482fccc7b624fd0be77a439d3b61f375f070d24c`
- Talos version: `0.10.5`
- Generated architecture evidence:
  `build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json`
- Scoping report:
  `work-cycle-docs/reports/t825-tool-loop-internals-boundary-scoping.md`

## Completed Scope

- Record current Wave 5 ordering for the remaining tool-loop internals.
- Identify candidate T826 owners without moving production code.
- Record higher-ranked non-toolcall hotspots deferred outside this seam.
- Cite the guard suites that must stay green before any T826 extraction.
- Treat `ToolCallSupport` split axes as hypotheses to validate, not decided
  ownership boundaries.

## Non-Goals

- No production source changes.
- No `LoopState`, `ToolCallSupport`, `ToolCallExecutionStage`,
  `ToolCallParseStage`, or `ToolCallRepromptStage` extraction.
- No `ToolCallLoop`, `LoopResult`, or `ToolOutcome` public API changes.
- No `ExecutionOutcome`, `TurnProcessor`, or `TaskContract` relocation.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.

## Candidate T826 Owners Reviewed

T825 reviewed these possible next seams:

- `LoopState` ownership hardening.
- `ToolCallExecutionStage` decomposition.
- `ToolCallSupport` split into narrower helpers, if the report proves a clean
  split.
- `ToolCallRepromptStage` boundary cleanup.

The selected next move is T826 `ToolCallExecutionStage` characterization. The
selection is based on direct source review: `ToolCallExecutionStage.execute(...)`
is public, behavior-heavy, directly used by `ToolCallLoopEngine`, and currently
lacks direct stage-level `execute(...)` characterization tests. T826 must be
characterization-only before any T827 production decomposition.

## Acceptance Criteria Evidence

- `ToolCallLoopInternalsBoundaryCharacterizationTest` passes.
- T825 report records evidence provenance and `INFERRED_REVIEW` confidence.
- T825 report names current tool-loop internal hotspots and deferred
  higher-ranked non-toolcall hotspots.
- T825 report names candidate T826 owners and explicit non-moves.
- T825 report cites the existing guard suites for T826.
- `dev.talos.runtime.toolcall.*`, `dev.talos.runtime.ToolCallLoop*`, full
  `check`, and `wikiEvidenceCloseGate --rerun-tasks` pass.
- `site/` remains untouched and unstaged.

Verified during closeout planning and implementation:

- `ToolCallLoopInternalsBoundaryCharacterizationTest`: pass.
- `check`: pass.
- `wikiEvidenceCloseGate --rerun-tasks`: pass.
- Generated architecture manifest anchored to
  `482fccc7b624fd0be77a439d3b61f375f070d24c`.
- Non-`site/` tree clean before closeout edits.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopInternalsBoundaryCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoop*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short -- . ':!site'
```
