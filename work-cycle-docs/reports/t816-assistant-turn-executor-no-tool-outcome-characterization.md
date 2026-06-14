# T816 AssistantTurnExecutor No-Tool Outcome Characterization

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T816 HEAD:
  `a1b261b62aaca7b619d6fb18ad032c3e37c9ceec`
- Talos version: `0.10.5`
- Ticket:
  `work-cycle-docs/tickets/open/[T816-open-high] assistant-turn-executor-no-tool-outcome-characterization.md`
- Source:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`

T816 is characterization-only. It does not authorize production extraction.

## Source Anchors

- `AssistantTurnExecutor.resolveNoToolAnswer(...)` starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:512`.
- `AssistantTurnExecutor.readEvidenceHandoffIfNeeded(...)` starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:611`.
- `AssistantTurnExecutor.readOnlyInspectionRetryIfNeeded(...)` overloads start
  at `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:636` and
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:650`.
- `AssistantTurnExecutor.emptyNoToolLoopResult(...)` starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:670`.
- `AssistantTurnExecutor.shapeAnswerAfterToolLoop(...)` starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:1630`.
- `AssistantTurnExecutor.shapeAnswerWithoutTools(...)` starts at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2064`.
- `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)` overloads start at
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2270` and
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2283`.

## Characterized Behavior

| Behavior | Characterization |
|---|---|
| Malformed protocol/debris retry | A malformed no-tool mutation response gets one bounded retry before the no-action shaping path. |
| Missing mutation retry | A mutation request that returns unsupported no-tool prose runs the bounded missing-mutation retry and exposes the retry tool summary. |
| Read-evidence handoff | A no-tool answer to an explicit read request runs a read handoff before final answer shaping. |
| Read-only inspection retry | A workspace-explain no-tool deflection runs read-only tools before the final answer. |
| Streaming evidence/mutation buffering | Evidence and mutation turns with a stream sink remain buffered so initial unsupported prose is not emitted. |

## T817 Candidate Owner

T817 may introduce package-private `AssistantNoToolOutcomeResolver` in
`dev.talos.cli.modes`.

Move later in T817:

- `resolveNoToolAnswer(...)` orchestration;
- `emptyNoToolLoopResult(...)`;
- malformed protocol/debris retry ordering;
- no-tool missing-mutation retry ordering;
- read-evidence handoff ordering;
- read-only inspection retry ordering;
- the point at which no-tool final shaping is invoked.

Keep in `AssistantTurnExecutor`:

- public `execute(...)`;
- streaming/buffered branch selection;
- trace begin/set/clear;
- tool-loop outcome path;
- direct-answer and unsupported preflight;
- `shapeAnswerWithoutTools(...)`;
- `shapeAnswerAfterToolLoop(...)`;
- `TurnOutput` assembly.

## Stop Conditions

- Stop if T816 characterization fails and the failure is not understood.
- Stop if a proposed T817 extraction changes public CLI behavior or the
  `AssistantTurnExecutor.execute(...)` signature.
- Stop if a proposed T817 extraction moves trace begin/set/clear.
- Stop if a proposed T817 extraction moves the tool-loop outcome path.
- Stop if a proposed T817 extraction moves `shapeAnswerWithoutTools(...)` or
  `shapeAnswerAfterToolLoop(...)` without separate characterization.
- Stop if `SetupCmd.java` is touched.
- Stop if the priority index is treated as a success metric rather than
  review-order evidence.

## Result

T816 pins the no-tool outcome boundary and names the future
`AssistantNoToolOutcomeResolver` owner. T816 does not authorize production
extraction by itself; T817 is the first possible extraction ticket.
