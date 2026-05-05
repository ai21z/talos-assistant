# T129 - Tool Metadata Migration And Tool Surface Planner

Severity: high
Status: done

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

## Architecture Metadata

- Capability: capability spine/tool surface.
- Operation(s): native tool-surface planning.
- Owning package/class: `dev.talos.runtime.toolcall.ToolSurfacePlanner`.
- New or changed tools: no new tools.
- Risk level: read/write metadata is consumed; destructive tools are not exposed by generic mutation apply.
- Approval behavior: unchanged.
- Protected path behavior: unchanged; protected read still receives read-file-only surface when target-bound.
- Checkpoint behavior: unchanged.
- Evidence obligation: unchanged.
- Verification profile: unchanged.
- Repair profile: unchanged; repair/evidence constrained surfaces continue through existing contracts.
- Outcome/truth warnings: unchanged.
- Trace/debug fields: prompt audit still receives native tool names through the existing plan path.
- Refactor scope: `NativeToolSpecPolicy` delegates to planner; executor fallback visible-tool list delegates to planner.
- Non-goals: no new tools, no command execution, no broad executor rewrite.

## Verification

- Focused unit tests for `ToolSurfacePlanner`.
- Existing tool-loop tests.
- `.\gradlew.bat --no-daemon build installDist`.
