# T826 ToolCallExecutionStage Characterization

Date: 2026-06-16
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Status: characterization-only

## Purpose

T826 pins direct behavior of
`dev.talos.runtime.toolcall.ToolCallExecutionStage.execute(...)` before any
production decomposition. The class is public, behavior-heavy, and directly
used by `ToolCallLoopEngine`.

T826 does not authorize production extraction.

## Architecture Evidence

T825 selected this target from regenerated architecture evidence anchored at
`482fccc7b624fd0be77a439d3b61f375f070d24c`.

Confidence: `INFERRED_REVIEW`.

Current relevant ranking at that evidence point:

| Candidate | Priority index |
|---|---:|
| `runtime.ToolCallLoop` | 334 |
| `runtime.toolcall.LoopState` | 292 |
| `runtime.toolcall.ToolCallSupport` | 235 |
| `runtime.toolcall.ToolCallExecutionStage` | 231 |

Higher-ranked non-toolcall hotspots remain deferred:

- `runtime.TurnProcessor` at `314`.
- `runtime.task.TaskContract` at `301`.
- `cli.modes.ExecutionOutcome` relocation remains later work.

## Public Surface To Preserve

- `public IterationOutcome execute(LoopState state, ToolCallParseStage.ParsedCalls parsed)`.
- `public record IterationOutcome(...)`.

The public `IterationOutcome` fields are part of the tool-loop iteration
contract and must remain stable until a later ticket explicitly changes public
runtime API.

## Characterized Behavior

The new test drives `ToolCallExecutionStage.execute(...)` directly and builds
`ParsedCalls` through `ToolCallParseStage.parse(...)`.

Pinned behavior:

- Text path appends an assistant message and a user `[tool_result: ...]`
  message.
- Native path appends an assistant native-tool-call message and a `tool` role
  result message using the native call id.
- Denied mutating calls set approval-denial and mutating-denial flags and
  record a denied `ToolOutcome`.
- Private-document named-target blocks positively fire before tool execution
  and private handoff: path-policy flag, failed pre-execution read outcome,
  blocked trace event, no approval prompt, and no read body.
- Successful execution increments successes, records a context-ledger
  decision, appends a result message, and records an executed outcome.
- Failed edit execution records failure and edit-repair accounting and does
  not report success.

## T827 Move/Stay Boundary

T827 may decompose the implementation behind the stable public surface, but
must not move behavior without preserving T826 observations.

Move candidates for T827:

- pre-execution guard handling;
- result-message append coordination;
- ledger/trace accounting helpers;
- failure/edit-repair accounting coordination.

Do not move yet:

- public `ToolCallExecutionStage.execute(...)`;
- public `IterationOutcome`;
- `LoopState`;
- `ToolCallSupport`;
- `ToolCallParseStage`;
- `ToolCallRepromptStage`;
- `ToolCallLoop.LoopResult`;
- `ToolCallLoop.ToolOutcome`;
- `ExecutionOutcome`.

## Guard Suites

T827 must keep these green:

- `ToolCallExecutionStageCharacterizationTest`
- `ToolCallLoopOrchestrationCharacterizationTest`
- `ToolCallLoopTest`
- `ToolCallLoopP0Test`
- `ToolCallLoopNativeTest`
- `ToolCallLoopCompactionTest`
- `dev.talos.runtime.toolcall.*`
- full `check`

