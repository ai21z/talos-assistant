# T132 - Batch Workspace Apply

Severity: medium
Status: open

## Problem

Many useful Talos tasks are coherent multi-path operations: create a docs workspace, scaffold a small app, move related files, or create a report folder. Applying these as unrelated one-off tool calls makes approval, checkpointing, and final summaries weaker.

## Scope

- Support coherent multi-file/folder operations with one approval.
- Add preview/summary of planned changes before apply.
- Use `WorkspaceOperationPlan`, `WorkspaceOperationResult`, and bundle checkpoints from T130.
- Preserve failure-dominant output on partial apply.

## Acceptance

- One approval can apply a coherent batch of workspace operations.
- Preview names all affected paths and operation kinds.
- Partial failure reports exact applied and failed paths.
- Bundle checkpoint id is recorded.
- Runtime-owned final summary is used instead of model-authored success prose.

## Non-Goals

- No shell command execution.
- No destructive recursive delete unless separately approved by policy.
- No UI beyond CLI approval/summary.

## Verification

- Focused tests for batch preview, approval, success, and partial failure.
- `.\gradlew.bat --no-daemon build installDist`.
