# T132 - Batch Workspace Apply

Severity: medium
Status: done

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

- Red test run first failed on missing `BatchWorkspaceApplyTool`, `WorkspaceBatchPlan`, and `WorkspaceBatchPlanParser`.
- Focused T132 tests passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.tools.impl.BatchWorkspaceApplyToolTest --tests dev.talos.runtime.workspace.WorkspaceBatchPlanParserTest --tests dev.talos.runtime.WorkspaceBatchTurnProcessorTest`
- Adjacent tool-surface/alias/runtime suite passed:
  `.\gradlew.bat --no-daemon test --tests dev.talos.tools.impl.BatchWorkspaceApplyToolTest --tests dev.talos.runtime.workspace.WorkspaceBatchPlanParserTest --tests dev.talos.runtime.WorkspaceBatchTurnProcessorTest --tests dev.talos.tools.impl.WorkspaceOperationToolsTest --tests dev.talos.runtime.WorkspaceOperationTurnProcessorTest --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.runtime.toolcall.NativeToolSpecPolicyTest --tests dev.talos.runtime.toolcall.ToolCallSupportTest --tests dev.talos.tools.ToolRegistryTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest`
- Full verification passed:
  `.\gradlew.bat --no-daemon build installDist`

## Completion Notes

- Added `talos.apply_workspace_batch` for coherent non-destructive workspace batches.
- Added JSON batch parsing and bundle checkpoint planning over affected paths.
- Wired batch paths into permission/protected-path checks so nested protected targets are denied before approval.
- Batch apply uses one approval and delegates each operation to the existing first-class workspace tools.
- Partial failure reports applied paths, failed path, and the runtime tool error.
