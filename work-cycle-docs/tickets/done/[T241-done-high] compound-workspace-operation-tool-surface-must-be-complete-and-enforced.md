# T241 - Compound Workspace Operation Tool Surface Must Be Complete And Enforced

Status: done

Closed: 2026-05-11

Severity: high

## Problem

For a compound workspace operation request containing mkdir, copy, rename, and move, Talos exposed only `talos.move_path`.

GPT-OSS failed because it only had the wrong single tool. Qwen succeeded only because it emitted hidden tools (`talos.mkdir`, `talos.copy_path`, `talos.rename_path`) that were not listed as visible/native for the turn.

This means the planner is too narrow and the executor is too permissive for text-parsed tool calls.

## Evidence

Audit:
`local/manual-testing/user-perspective-broad-reaudit-20260511-143729/FINDINGS-USER-PERSPECTIVE-BROAD-REAUDIT.md`

Transcript:
- Qwen frame says only `talos.move_path`: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3899`
- Qwen executed hidden workspace tools anyway: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3946`
- GPT-OSS frame says only `talos.move_path`: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:4393`
- GPT-OSS native tools only `talos.move_path`: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:4395`
- GPT-OSS failed after repeated move attempts: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:4434`

## Scope

- Detect compound workspace operation requests and expose a complete tool surface:
  - either `talos.apply_workspace_batch`,
  - or all required individual tools: `talos.mkdir`, `talos.copy_path`, `talos.rename_path`, `talos.move_path`, and `talos.delete_path` when needed.
- Enforce the current-turn visible/native tool allowlist at execution time for both native and text-parsed tool calls.
- If a model emits a hidden tool, reject it before execution with a deterministic policy error.
- Keep successful simple one-operation requests working.

## Acceptance

- The compound mkdir/copy/rename/move prompt exposes a complete workspace-operation tool surface.
- A GPT-OSS-shaped scripted model can satisfy the compound request via `talos.apply_workspace_batch` or correctly exposed individual operation tools.
- A Qwen-shaped scripted model that emits a hidden tool not visible in the current turn is rejected before execution.
- The changed-files summary remains runtime-owned and accurate.
- Targeted tests and full Gradle tests pass.

## Resolution

- `WorkspaceOperationIntent` now detects compound workspace-operation turns instead of collapsing them to the first single operation.
- Compound workspace operation turns expose `talos.apply_workspace_batch` plus the required individual workspace tools (`talos.mkdir`, `talos.copy_path`, `talos.rename_path`, `talos.move_path`, and `talos.delete_path` only when a delete operation is requested).
- `TurnProcessor` now enforces the current `Context.nativeToolSpecs()` allowlist before executing a registered tool. A registered but hidden tool is rejected with a deterministic `DENIED` result and trace block instead of being executed.
- The phase-policy test harness now wires the same registry into `Context` that it gives to `TurnProcessor`, matching production planning assumptions.

## Verification

- `.\gradlew test --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest.compoundWorkspaceOperationRequestsExposeBatchAndRequiredOperationTools --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest.compoundWorkspaceTurnSendsCompleteWorkspaceOperationSurface --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.hiddenWorkspaceOperationToolIsRejectedBeforeExecution' --no-daemon`
- `.\gradlew test --tests dev.talos.runtime.toolcall.ToolSurfacePlannerTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.hiddenWorkspaceOperationToolIsRejectedBeforeExecution' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.compoundWorkspaceOperationCanApplyBatchThroughVisibleSurface' --no-daemon`
- `.\gradlew test --tests dev.talos.cli.modes.AssistantTurnExecutorPhasePolicyTest.explicitMutationTurnStartsInApplyAndMovesToVerifyAfterSuccessfulMutation --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
