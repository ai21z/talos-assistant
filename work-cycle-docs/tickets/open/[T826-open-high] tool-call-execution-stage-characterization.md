# [T826-open-high] ToolCallExecutionStage Characterization

Status: open
Priority: high
Date: 2026-06-16
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T825-done-high] tool-loop-internals-boundary-scoping`

## Why This Ticket Exists

T825 scoped the remaining `runtime.toolcall` internals after the
`ToolCallLoopEngine` extraction. The next narrowest behavior-heavy seam is
`ToolCallExecutionStage.execute(...)`.

The stage owns parse-to-execute side effects inside a tool-loop iteration:
assistant/result message shape, approval denial classification, pre-execution
path-policy blocks, context-ledger decisions, mutation/failure accounting, and
edit-repair accounting. It is public and currently consumed by
`ToolCallLoopEngine`, but it did not have direct `execute(...)`
characterization before T826.

## Scope

In scope:

- Add direct stage-level characterization tests for
  `ToolCallExecutionStage.execute(...)`.
- Drive parsing through `ToolCallParseStage.parse(...)`.
- Preserve public `execute(...)` and public `IterationOutcome` surface.
- Record the T827 move/stay boundary.

Out of scope:

- No production extraction.
- No behavior change.
- No `LoopState`, `ToolCallSupport`, `ToolCallExecutionStage`,
  `ToolCallParseStage`, `ToolCallRepromptStage`, `ExecutionOutcome`, or tool
  model relocation.
- No Qodana change.
- No candidate recut.
- No `SetupCmd.java` edits.

## Characterization Targets

- Text tool-call path appends an assistant message and a user
  `[tool_result: ...]` result message.
- Native tool-call path appends an assistant tool-call message and a `tool`
  role result with the native call id.
- Denied mutating tool calls set approval-denial and mutating-denial flags and
  record a denied `ToolOutcome`.
- Private-document named-target blocks fire before tool execution/model
  handoff and record path-policy block evidence.
- Successful execution records a context-ledger decision, appends the model
  result message, increments successes, and records an executed outcome.
- Failed edit execution records failure/edit-repair accounting without
  reporting success.

## T827 Preview

T826 does not authorize extraction by itself.

If T826 is green and reviewed, T827 may decompose the execution stage behind
stable public surface. T827 must preserve:

- `public IterationOutcome execute(LoopState state, ToolCallParseStage.ParsedCalls parsed)`.
- `public record IterationOutcome(...)`.
- Current text/native result-message shape.
- Approval, path-policy, trace, ledger, mutation, and failure accounting order.

Likely move candidates for T827:

- pre-execution guard handling;
- result-message append coordination;
- ledger/trace accounting helpers;
- failure/edit-repair accounting coordination.

Keep stable until separately scoped:

- public `ToolCallExecutionStage.execute(...)`;
- public `IterationOutcome`;
- `LoopState`;
- `ToolCallSupport`;
- `ToolCallParseStage`;
- `ToolCallRepromptStage`;
- `ToolCallLoop.LoopResult`;
- `ToolCallLoop.ToolOutcome`;
- `ExecutionOutcome`.

## Acceptance Criteria

- `ToolCallExecutionStageCharacterizationTest` directly drives
  `ToolCallExecutionStage.execute(...)`.
- T826 adds no `src/main` changes.
- Focused `runtime.toolcall` and `ToolCallLoop` gates pass.
- Full `check` passes.
- `wikiEvidenceCloseGate --rerun-tasks` passes.
- `site/` remains untouched and unstaged.

## Verification

Planned:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallExecutionStageCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoop*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short -- . ':!site'
```
