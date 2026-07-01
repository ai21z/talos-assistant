# T814 AssistantTurnExecutor Tool-Loop Outcome Characterization

Evidence identity:

- Branch: `v0.9.0-beta-dev`
- Pre-T814 HEAD: `c8e53006556045f4af18c2a407741f36da093305`
- Talos version: `0.10.5`
- Generated architecture evidence: `cli.modes.AssistantTurnExecutor` remains
  Wave 5 rank 1 with priority index `384`
  (`hotspot=273`, `lifecycle=56`, `approvalTool=30`,
  `tracePrivacy=25`, `INFERRED_REVIEW`).
- Active ticket:
  `work-cycle-docs/tickets/done/[T814-done-high] assistant-turn-executor-tool-loop-outcome-characterization.md`

The priority index is review-order evidence only. It is not a success metric,
grade, or extraction mandate.

T814 does not authorize production extraction. It characterizes current
behavior so T815 can move one ownership boundary with tests already in place.

## Current Source Anchors

The post-tool-loop outcome path currently crosses these anchors in
`AssistantTurnExecutor`:

- streaming tool-loop call site:
  `ctx.toolCallLoop().run(...)` inside the streaming branch;
- buffered tool-loop call site:
  `ctx.toolCallLoop().run(...)` inside the buffered branch;
- shared post-loop resolver: `resolveToolLoopAnswer(...)`;
- visible summary composer: `visibleToolLoopSummary(...)`;
- final post-tool shaping: `shapeAnswerAfterToolLoop(...)`;
- post-tool synthesis retry: `synthesisRetryIfNeeded(...)`;
- missing-mutation retry delegate: `mutationRequestRetryIfNeeded(...)`;
- inspect-completeness retry delegate: `inspectCompletenessRetryIfNeeded(...)`;
- read-evidence recovery:
  `readEvidenceRecoveryForPartialTargetsIfNeeded(...)`;
- verify phase transition: `moveToVerifyAfterSuccessfulMutation(...)`.

## Behavior Characterized In T814

| Behavior | Characterization test | Why it matters |
|---|---|---|
| Post-tool synthesis retry runs after tool evidence and before final outcome shaping. | `postToolLoopSynthesisRetryRunsBeforeOutcomeShaping` | A model deflection after tool use must not survive as the final answer when a synthesis retry produces a grounded answer. |
| Missing-mutation retry can run after an initial tool loop and its retry loop evidence is used for the final answer and visible summary. | `postToolLoopMissingMutationRetryUsesRetryLoopEvidenceForFinalAnswer` | A mutation request that only inspected first must still execute the required write/edit before Talos claims completion. |
| Inspect-completeness retry can merge read evidence from an additional tool loop and expose one visible summary. | `postToolLoopInspectCompletenessRetryMergesRetryReadEvidenceAndSingleSummary` | Read evidence from retry loops must not be lost or duplicated when final answers are shaped. |
| Approval-denied mutating outcomes suppress missing-mutation retry. | `postToolLoopDeniedMutationDoesNotTriggerMissingMutationRetry` | A denied write/edit must end as a denial/no-change outcome, not silently trigger another write attempt. |
| The T815 move/stay boundary is explicit. | `toolLoopOutcomeReportPinsMoveStayBoundary` | The next ticket must extract the resolver boundary, not `LoopResult`, `ToolOutcome`, or unrelated executor ownership. |

## T815 Candidate Owner

Candidate owner: package-private `AssistantToolLoopOutcomeResolver` in
`dev.talos.cli.modes`.

Move later in T815:

- `resolveToolLoopAnswer(...)`;
- `visibleToolLoopSummary(...)`;
- post-loop orchestration of synthesis retry, missing-mutation retry,
  inspect-completeness retry, read-evidence recovery, verify-phase transition,
  and final `shapeAnswerAfterToolLoop(...)` call.

Keep in `AssistantTurnExecutor` until separately proven movable:

- public `execute(...)`;
- streaming/buffered branch selection;
- `ctx.toolCallLoop().run(...)`;
- trace begin/set/clear;
- no-tool outcome path;
- direct-answer and unsupported preflight;
- `TurnOutput` assembly.

Do not move `ToolCallLoop.LoopResult` or `ToolOutcome` in T815. Earlier
tool-loop outcome value work, including T543 and T549, already established that
those compatibility surfaces should not be moved casually.

## Stop Conditions

Stop before T815 extraction if:

- a characterization test fails and the failure is not understood;
- extraction would change public CLI behavior or
  `AssistantTurnExecutor.execute(...)`;
- extraction would move retry decisions without preserving the ordering proved
  by T814;
- extraction would move trace begin/set/clear ordering;
- extraction would pull `ToolCallLoop` execution ownership into the new
  collaborator;
- extraction would touch `SetupCmd.java`;
- the priority index is treated as a pass/fail target instead of review-order
  evidence.

## Current Result

T814 is complete as a characterization-only ticket. It added tests and docs
that make T815 safe to implement as a separate production extraction. No
production extraction was made by T814.
