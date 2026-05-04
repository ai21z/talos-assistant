# T130 - Workspace Operation Plan And Bundle Checkpoints

Severity: high
Status: open

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

## Verification

- Focused checkpoint/operation-plan tests.
- Existing checkpoint tests.
- `.\gradlew.bat --no-daemon build installDist`.
