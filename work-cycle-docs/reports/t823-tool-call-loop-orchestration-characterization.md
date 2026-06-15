# T823 ToolCallLoop Orchestration Characterization

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T823 HEAD:
  `33e3998bdb2dcea74a0bbdde7dcf8c3b59626f23`
- Talos version: `0.10.5`
- Ticket:
  `work-cycle-docs/tickets/open/[T823-open-high] tool-call-loop-orchestration-characterization.md`
- Source:
  `src/main/java/dev/talos/runtime/ToolCallLoop.java`

T823 is characterization-only. T823 does not authorize production extraction.

## Source Anchors

- `ToolCallLoop.DEFAULT_MAX_ITERATIONS` is declared at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:38`.
- `ToolCallLoop.LoopResult` starts at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:69`.
- `ToolCallLoop.ToolOutcome` starts at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:176`.
- The public text-entry `run(...)` overload starts at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:314`.
- The native/text orchestration `run(..., List<NativeToolCall>, ...)` overload
  starts at `src/main/java/dev/talos/runtime/ToolCallLoop.java:318`.
- The no-tool zeroed result path returns at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:329`.
- Loop state and stage construction occur at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:334` through
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:346`.
- Parse, execute, reprompt, iteration-limit, finalization, and result
  construction are anchored at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:349`,
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:381`,
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:387`,
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:393`, and
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:405`.
- Static helper delegates start at
  `src/main/java/dev/talos/runtime/ToolCallLoop.java:414`.

## Characterized Behavior

| Behavior | Characterization |
|---|---|
| No tool calls | Returns the input answer with zero iterations, zero tools, no failures, no outcomes, and unchanged messages. |
| Text tool calls | Parses the JSON code-fence fallback format, appends the assistant tool-call message, executes the tool, appends a user-visible text tool result message, reprompts, and returns the final text. |
| Native tool calls | Starts from `ChatMessage.NativeToolCall`, appends an assistant native-tool-call message, appends a correlated `role=tool` result message, reprompts, and returns the same public `LoopResult` shape. |
| Iteration limit | Stops after `maxIterations`, sets `hitIterLimit`, strips raw tool-call payload from the final answer, and appends the runtime limit notice. |
| Public compatibility surface | `LoopResult`, `ToolOutcome`, public constructors, public `run(...)` overloads, and existing static helper delegates remain owned by `ToolCallLoop` for T824. |

## T824 Candidate Owner

T824 may introduce package-private `ToolCallLoopEngine` in
`dev.talos.runtime.toolcall`.

Move later in T824:

- body of `ToolCallLoop.run(..., List<NativeToolCall>, ...)`;
- loop state creation;
- parse, execute, and reprompt stage orchestration;
- iteration-limit handling;
- finalization call ordering;
- alias-rescue cushion calculation;
- `LoopResult` construction.

Keep in ToolCallLoop:

- public constructors;
- public `run(...)` overloads;
- `DEFAULT_MAX_ITERATIONS`;
- public nested `LoopResult`;
- public nested `ToolOutcome`;
- existing static helper delegates.

Keep outside T824 unless separately scoped:

- `ToolCallSupport`;
- `ToolCallExecutionStage`;
- `LoopState`;
- `ExecutionOutcome`;
- tool model types.

## Stop Conditions

- Stop if T823 characterization fails and the failure is not understood.
- Stop if a proposed T824 extraction changes the public `ToolCallLoop` API.
- Stop if a proposed T824 extraction moves `LoopResult` or `ToolOutcome`.
- Stop if a proposed T824 extraction moves `ToolCallSupport`,
  `ToolCallExecutionStage`, `LoopState`, `ExecutionOutcome`, or tool model
  types.
- Stop if the priority index is treated as a success metric rather than
  review-order evidence.

## Result

T823 pins the `ToolCallLoop` orchestration boundary and names the future
`ToolCallLoopEngine` owner. T823 does not authorize production extraction by
itself; T824 is the first possible extraction ticket.
