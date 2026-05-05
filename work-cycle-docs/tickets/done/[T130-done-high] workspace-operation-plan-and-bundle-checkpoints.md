# T130 - Workspace Operation Plan And Bundle Checkpoints

Severity: high
Status: done

## Problem

Current checkpointing is centered on one file mutation. Workspace organization tools such as move, copy, rename, delete, and batch apply need multi-path planning and checkpoint support before implementation.

## Evidence

- Architecture spec: `docs/superpowers/specs/2026-05-04-talos-capability-spine-workspace-architecture-design.md`
- Current `FileBundleCheckpointStore` is single-target oriented.

## Scope

- Add internal `WorkspaceOperationPlan` and `WorkspaceOperationResult`.
- Design or implement bundle checkpoint support for multi-path operations.
- Represent source paths, destination paths, absent-before paths, deleted paths, overwrite policy, recursive flag, approval summary, and preview summary.
- Preserve existing single-file checkpoint behavior.

## Acceptance

- Tests cover planned multi-path operations without applying them.
- Bundle checkpoint can represent source, destination, and deleted paths.
- Existing single-file checkpoints continue working.
- Operation result can report applied, failed, skipped, partial, blocked, and checkpoint id.

## Non-Goals

- No public move/copy/delete tools yet unless explicitly split.
- No shell command checkpoints.
- No broad checkpoint store rewrite beyond the operation-plan need.

## Architecture Metadata

- Capability: workspace operation planning/checkpointing.
- Operation(s): internal plan/result records and bundle checkpoint capture.
- Owning package/class: `dev.talos.runtime.workspace`, `dev.talos.runtime.checkpoint`.
- New or changed tools: no new tools.
- Risk level: plans carry read/write/destructive risk metadata; public behavior unchanged.
- Approval behavior: unchanged; plans carry approval summaries for later tools.
- Protected path behavior: unchanged.
- Checkpoint behavior: `CheckpointService` and `FileBundleCheckpointStore` can capture multi-path operation plans; single-file checkpoint API remains.
- Evidence obligation: none.
- Verification profile: none.
- Repair profile: none.
- Outcome/truth warnings: operation results can carry applied/partial/blocked/failed/skipped state for later rendering.
- Trace/debug fields: checkpoint ids remain available through existing capture result.
- Refactor scope: additive internal API plus shared checkpoint capture helper.
- Non-goals: no public move/copy/delete tools, no shell command checkpoints, no broad checkpoint rewrite.

## Verification

- Focused checkpoint/operation-plan tests.
- Existing checkpoint tests.
- `.\gradlew.bat --no-daemon build installDist`.
