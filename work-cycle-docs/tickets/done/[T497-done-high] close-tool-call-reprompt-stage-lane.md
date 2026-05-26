# [T497-done-high] Close Tool-Call Reprompt Stage Lane

## Status

Done.

## Scope

T497 reinspects `ToolCallRepromptStage` after T496 extracted
`ToolFailurePolicyStopAnswer` and records whether the current reprompt-stage
lane should continue.

This is a no-code closeout ticket. It does not change runtime behavior,
tool-call ordering, outcome wording, repair planning, failure policy,
approval behavior, protected path behavior, context-budget handling,
trace wording, or tool-surface narrowing.

## Current Shape

Source inspection on fresh `origin/v0.9.0-beta-dev` after T496:

| Source | Finding |
| --- | --- |
| `ToolCallRepromptStage.java` | 590 lines |
| `ToolRepromptRequestBuilder` | owns reprompt request assembly and tool narrowing |
| `ToolRepromptContextBudgetHandler` | owns context-budget fallback and compact budget stops |
| `StaticWebContinuationPlanner` | owns post-mutation static-web continuation decisions |
| `ExpectedTargetProgressAccounting` | owns expected-target remaining-target accounting |
| `DeniedMutationResponseOnlySynthesizer` | owns non-approval denied-mutation response-only synthesis |
| `ToolFailurePolicyStopAnswer` | owns failure-policy stop answer rendering |

The broad reprompt-stage lane has removed the major non-orchestration owners
that were safe to extract:

- request construction;
- static-web continuation planning;
- post-T491 context-budget fallback;
- expected-target progress accounting;
- denied-mutation response-only synthesis;
- failure-policy stop answer rendering.

## What Should Stay In `ToolCallRepromptStage`

The remaining code is mostly live sequencing:

- approval denial versus policy denial versus path-policy block ordering;
- expected-target scope repair before a hard pre-approval path-policy stop;
- terminal read-only stop selection before mutation-continuation checks;
- all-success mutation continuation versus static-web verification success;
- partial-success mutation re-prompt behavior;
- repair/read-only budget stops before generic failure-policy stops;
- source-evidence and target-readback compact repair planner ordering;
- temporary prompt insertion and cleanup around a single reprompt call;
- transient retry handling for the actual continuation call.

This is not cleanly extractable as independent domain policy. It is the current
tool-loop continuation choreography.

## Rejected Next Extractions

### Repair-Budget Predicates

Do not extract `repairReadOnlyBudgetExceeded(...)` or
`mutationReadOnlyBudgetExceeded(...)` directly from `ToolCallRepromptStage` in
the next ticket.

Those branches are mixed ownership:

- task-contract interpretation;
- static-repair context;
- workspace-operation exemptions;
- compact mutation evidence continuation;
- conditional review/fix no-change answer;
- action-obligation trace recording;
- deterministic repair-inspection answer wording.

That needs a boundary decision before implementation.

### Temporary Prompt Lifecycle

Do not extract the current-task, expected-target progress, static-repair
progress, stale-edit repair, or empty-edit repair prompt lifecycle now.

The cleanup order is tied to the exact insertion order in the live reprompt
call. Moving it as a mechanical helper would hide ordering risk without
clarifying ownership.

### Static Repair Remaining Targets

Do not move `remainingFullRewriteRepairTargets(...)` yet. It is still coupled
to static repair context, successful mutation evidence, and pending obligation
selection.

### Continuation Chat Call

Do not extract `chatReprompt(...)`, `chatRepromptResult(...)`, or transient
retry handling yet. These own the actual LLM continuation IO and exact error
wording; they are not a policy boundary.

## Decision

Close the tool-call reprompt-stage extraction lane.

Future work should not open another `ToolCallRepromptStage` extraction ticket
unless a fresh decision ticket identifies a coherent owner with behavior and
wording regression tests.

## Next Hygiene Lane

The next correct ticket should be a decision/inspection ticket:

```text
[T498] Read-only repair budget boundary decision
```

T498 should inspect, without implementation:

- `ToolCallRepromptStage.repairReadOnlyBudgetExceeded(...)`;
- `ToolCallRepromptStage.mutationReadOnlyBudgetExceeded(...)`;
- `ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(...)`;
- `CompactMutationContinuationPlanner`;
- `ConditionalReviewFixPolicy`;
- `ResponseObligationVerifier.deterministicRepairInspectionOnlyAnswer()`;
- relevant `ToolCallLoopTest`, `ToolCallRepromptStageTest`,
  `ToolRepromptContextBudgetHandlerTest`, and repair/conditional review tests.

The decision should answer whether the next implementation owner is:

- a repair-budget gate;
- a mutation-evidence read-only budget gate;
- a conditional review/fix terminal answer owner;
- an action-obligation trace owner;
- or no implementation yet.

Do not start by moving code.
