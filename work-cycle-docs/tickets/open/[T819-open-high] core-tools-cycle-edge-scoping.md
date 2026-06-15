# T819 Core-Tools Cycle Edge Scoping

Status: open
Priority: high
Wave: 5
Owner: architecture/package boundary evidence

## Summary

Scope the remaining top-level `core <-> tools` package cycle before any
production cycle-break work.

T819 is report-only. It exists to make T820 decision-ready from current
architecture evidence instead of stale roadmap claims. It does not move
classes, change package ownership, change runtime behavior, or baseline any
architecture rule.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T819 HEAD:
  `d1371693cdf25873a5fa477dd8bce17f5e5c500f`
- Talos version: `0.10.5`
- Generated architecture report:
  `build/reports/talos/architecture-intelligence/current/03-package-boundary-and-cycle-map.md`
- Scoping report:
  `work-cycle-docs/reports/t819-core-tools-cycle-edge-scoping.md`

The generated package-edge counts are package-level architecture evidence.
They are not the same thing as explicit source import counts.

## Scope

- Record the current top-level package SCC evidence.
- Enumerate the current generated `core -> tools` and `tools -> core` package
  edge counts.
- Enumerate the smaller explicit source-import direction from `core` into
  `tools`.
- Identify candidate seams for T820.
- State which seams are low-blast-radius candidates and which require more
  ownership review.

## Current Evidence To Scope

- One top-level SCC exists: `{core, tools}`.
- Generated matrix:
  - `core -> tools = 8`;
  - `tools -> core = 40`.
- Explicit smaller direction: 3 core files / 6 imports:
  - `core.context.ContextItem`;
  - `core.rag.RagService`;
  - `core.llm.SystemPromptBuilder`.

## Non-Goals

- No production source changes.
- No class or package moves.
- No `ToolCallLoop`, `LoopState`, `LoopResult`, or `ToolOutcome` moves.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.
- No architecture baseline expansion.
- No claim that the cycle break is cheap until the T819 report proves the
  target seam.

## Acceptance Criteria

- The T819 report names the current SCC and generated matrix evidence.
- The report lists the explicit `core -> tools` import sites and their
  ownership meaning.
- The report recommends candidate seams for T820 without performing the
  production refactor.
- The wiki records T819 as the active report-only scoping ticket.

## Verification

Run:

```powershell
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short -- . ':!site'
```
