# [T365-done-medium] Move Batch Workspace Apply Tool To Runtime Workspace

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T365`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T364-done-medium] move-run-command-tool-to-runtime-command`

## Evidence Summary

- Source: post-T364 implementation after PR #29 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `cf85b8518e047eec545a802904b95ce4b92c08d8`.
- Beta push CI: run `#83`, `Beta Dev CI`, push event for `cf85b85`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T365`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved `BatchWorkspaceApplyTool` from `dev.talos.tools.impl` to
    `dev.talos.runtime.workspace`;
  - moved `BatchWorkspaceApplyToolTest` with it;
  - updated CLI bootstrap, prompt render, E2E harness, and tests to import the
    runtime-owned batch workspace tool;
  - removed three stale tools-to-runtime baseline rows.
- Verification status: passed.

## Problem

`BatchWorkspaceApplyTool` was a runtime workspace-operation adapter living in
the lower `tools.impl` package while importing runtime workspace planning
types:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchOperation
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlan
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlanParser
```

That package placement was false. The tool's behavior is defined by runtime
workspace batch planning, checkpoint planning, and approval-visible operation
metadata. The generic tool implementation package should not own a tool whose
contract is already runtime-workspace specific.

## Change

T365 moves:

```text
dev.talos.tools.impl.BatchWorkspaceApplyTool
```

to:

```text
dev.talos.runtime.workspace.BatchWorkspaceApplyTool
```

The tool still implements `TalosTool` and still registers the same
`talos.apply_workspace_batch` native tool name. The implementation continues to
delegate each concrete file operation to the existing first-class workspace
tools, preserving behavior while putting the batch adapter beside the runtime
workspace batch plan/parser it depends on.

## Baseline Result

Architecture baseline moved:

```text
22 -> 19
```

Removed entries:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchOperation
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlan
tools-no-runtime|src/main/java/dev/talos/tools/impl/BatchWorkspaceApplyTool.java|dev.talos.runtime.workspace.WorkspaceBatchPlanParser
```

## Tests Updated

- `BatchWorkspaceApplyToolTest` moved to `dev.talos.runtime.workspace`.
- Existing bootstrap, prompt-render, E2E harness, tool-surface,
  task-contract, registry, and static-verifier tests now import
  `dev.talos.runtime.workspace.BatchWorkspaceApplyTool`.

## Verification

- RED architecture ratchet:
  `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with the three removed `BatchWorkspaceApplyTool` baseline
  rows before the move.
- Focused GREEN test run:
  `.\\gradlew.bat test --tests "dev.talos.runtime.workspace.BatchWorkspaceApplyToolTest" --tests "dev.talos.runtime.workspace.WorkspaceBatchPlanParserTest" --tests "dev.talos.runtime.WorkspaceBatchTurnProcessorTest" --tests "dev.talos.runtime.verification.WorkspaceOperationStaticVerifierTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --tests "dev.talos.runtime.toolcall.NativeToolSpecPolicyTest" --tests "dev.talos.tools.ToolRegistryTest" --no-daemon`:
  passed.
- `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.
- Final full verification before commit:
  `git diff --check` and `.\\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

After T365, inspect the remaining `19` baseline entries before choosing T366.
Do not mechanically attack `ReadFileTool -> PrivateDocumentPolicy`,
`DocumentExtractionService -> PrivateDocumentPolicy`, runtime-to-CLI session
memory/context edges, RAG context-ledger edges, or SPI purity without source
evidence.

Likely next tracks:

- finish the private-document policy ownership track with a narrow adopter only
  if the decision contract is already sufficient;
- start a CLI/runtime session-memory decision if the remaining runtime-to-CLI
  edges are now the dominant ownership problem;
- keep SPI purity as a separate design packet.

Confidence: high.
