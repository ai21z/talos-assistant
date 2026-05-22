# [T364-done-medium] Move Run Command Tool To Runtime Command

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T364`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T363-done-medium] move-result-contract-to-runtime`

## Evidence Summary

- Source: post-T363 implementation after PR #28 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `848973b62cf717a6dd850698d94030984e611aec`.
- Beta push CI: run `#80`, `Beta Dev CI`, push event for `848973b6`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T364`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved `RunCommandTool` from `dev.talos.tools.impl` to
    `dev.talos.runtime.command`;
  - moved `RunCommandToolTest` with it;
  - updated bootstrap, prompt-render, E2E harness, and tests to import the
    runtime-owned command tool;
  - removed eight stale tools-to-runtime baseline rows.
- Verification status: passed.

## Problem

`RunCommandTool` was a runtime command-profile adapter living in the lower
`tools.impl` package while importing runtime command planning, execution, and
trace capture:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlan
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlanRejectedException
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandProfileRegistry
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandResult
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandRunner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandToolPlanner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.ProcessCommandRunner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.trace.LocalTurnTraceCapture
```

That was architecturally inverted. The generic tools package should not own a
tool whose behavior is defined by runtime command policy, command-profile
validation, process execution, and local turn tracing.

## Change

T364 moves:

```text
dev.talos.tools.impl.RunCommandTool
```

to:

```text
dev.talos.runtime.command.RunCommandTool
```

This keeps the runtime command track together:

- command profile planning and validation;
- command runner abstraction and process runner;
- command result rendering for the tool response;
- command trace capture;
- runtime/CLI composition that registers the command tool.

The tool still implements `TalosTool`; the registration points continue to
register the same `talos.run_command` tool name. Behavior is unchanged.

## Baseline Result

Architecture baseline moved:

```text
30 -> 22
```

Removed entries:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlan
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandPlanRejectedException
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandProfileRegistry
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandResult
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandRunner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.CommandToolPlanner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.command.ProcessCommandRunner
tools-no-runtime|src/main/java/dev/talos/tools/impl/RunCommandTool.java|dev.talos.runtime.trace.LocalTurnTraceCapture
```

## Tests Updated

- `RunCommandToolTest` moved to `dev.talos.runtime.command`.
- Existing command-tool wiring, trace, prompt, and metadata tests now import
  `dev.talos.runtime.command.RunCommandTool`.

## Verification

- RED architecture ratchet:
  `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with the eight removed `RunCommandTool` baseline rows.
- Focused GREEN test run:
  `.\gradlew.bat test --tests "dev.talos.runtime.command.RunCommandToolTest" --tests "dev.talos.runtime.TurnProcessorCommandPolicyTest" --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --tests "dev.talos.tools.ToolOperationMetadataTest" --tests "dev.talos.cli.prompt.PromptInspectorTest" --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon`:
  passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.
- Final full verification before commit:
  `git diff --check` and `.\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

After T364, inspect the remaining `22` baseline entries. Do not mechanically
attack `Context`, `SessionMemory`, or private-document policy without source
evidence.

Likely next tracks:

- `BatchWorkspaceApplyTool` still imports runtime workspace planning types;
- `ReadFileTool` still imports runtime private-document policy;
- runtime still imports CLI `Context`, `ModeController`, and `SessionMemory`;
- SPI purity remains separate;
- RAG context-ledger ownership remains separate.

The next implementation ticket should be chosen by inspecting whether
`BatchWorkspaceApplyTool` is another runtime workspace adapter in the wrong
package, or whether that cluster needs a decision ticket first.

Confidence: high.
