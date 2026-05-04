# T129 - Tool Metadata Migration And Tool Surface Planner

Severity: high
Status: open

## Problem

Tool-surface decisions are currently spread across runtime/executor paths. As Talos adds more tools, visibility must be derived from capability metadata and current-turn policy, not scattered lists.

## Evidence

- Architecture spec: `docs/superpowers/specs/2026-05-04-talos-capability-spine-workspace-architecture-design.md`
- T128 capability metadata dependency.

## Scope

- Introduce `ToolSurfacePlanner` as a service boundary.
- Migrate existing read/write tool visibility decisions to consume capability metadata.
- Preserve repair/evidence constrained tool surfaces.
- Preserve provider request controls and prompt audit reporting.

## Acceptance

- Existing read-only and mutation tool visibility behavior remains unchanged.
- Repair/evidence constrained surfaces still work.
- Prompt audit still reports native and prompt tools accurately.
- `AssistantTurnExecutor` loses direct responsibility for at least one class of tool-surface decision.
- Tests cover representative small talk, read-only, mutation, protected-read, and repair turns.

## Non-Goals

- No new tools.
- No command execution.
- No broad executor rewrite.

## Verification

- Focused unit tests for `ToolSurfacePlanner`.
- Existing tool-loop tests.
- `.\gradlew.bat --no-daemon build installDist`.
