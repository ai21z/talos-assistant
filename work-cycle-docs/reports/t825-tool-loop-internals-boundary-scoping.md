# T825 Tool-Loop Internals Boundary Scoping

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- T824 implementation commit:
  `2d4a9611ad7357cb50f080d5b9c468a5a824f06e`
- T824 closeout commit:
  `85d53c372f2c6a76aea1be9b04aa212489cfe5c7`
- Talos version: `0.10.5`
- Generated architecture report:
  `build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json`
- Confidence label: `INFERRED_REVIEW`
- Ticket:
  `work-cycle-docs/tickets/open/[T825-open-high] tool-loop-internals-boundary-scoping.md`

T825 is scoping/characterization only. T825 does not authorize production extraction.

## Current Hotspot Evidence

The post-T824 generated evidence is an unnormalized priority index used for
review ordering, not a success metric.

| Candidate | Priority index | Confidence | T825 meaning |
|---|---:|---|---|
| ToolCallLoop | `334` | `INFERRED_REVIEW` | Public facade pressure remains after T824; do not keep extracting from the facade without a new seam. |
| LoopState | `292` | `INFERRED_REVIEW` | Highest remaining `runtime.toolcall` internal pressure. |
| ToolCallSupport | `235` | `INFERRED_REVIEW` | Broad helper surface that may need narrower ownership. |
| ToolCallExecutionStage | `231` | `INFERRED_REVIEW` | Large execution-stage owner with policy, approval, trace, accounting, and tool-result handoff behavior. |

## Deferred Higher-Ranked Non-Toolcall Hotspots

These outrank some tool-loop internals but are outside the T825 seam.

| Candidate | Priority index | Why deferred |
|---|---:|---|
| TurnProcessor | `314` | Session/tool execution owner; not part of the remaining `runtime.toolcall` boundary decision. |
| TaskContract | `301` | Turn contract model and resolver pressure; should not be mixed into tool-loop internals cleanup. |

## Candidate T826 Owners

T825 should select one production seam for T826 only after this report is
reviewed.

| Candidate owner | Current signal | T826 caution |
|---|---|---|
| `LoopState` ownership hardening | High priority index and shared mutable state used by execution, reprompt, guard, accounting, and repair helpers. | Do not casually hide fields without updating all guard tests that construct state directly. |
| `ToolCallExecutionStage` decomposition | Large execution-stage class owns pre-approval guards, execution, result messages, trace, context ledger, and failure/mutation accounting. | Preserve approval denial, protected path blocking, trace records, context ledger decisions, and native/text result message shape. |
| `ToolCallSupport` split hypotheses | Method clusters include native-call conversion, result formatting, path/call repair, request extraction, and compaction utilities. | Treat the retry-message utility split as a hypothesis; validate whether it is a real ownership seam before moving code. |
| `ToolCallRepromptStage` boundary cleanup | Reprompt owns stop/continue ordering across approval denial, path policy, terminal read-only answers, mutation success, evidence repair, budgets, and overlay continuation. | Do not change retry ordering or final answer shape while splitting. |

## Do Not Move In T825

- No production source.
- No `ToolCallLoop`, `LoopResult`, or `ToolOutcome` public API changes.
- No `LoopState`, `ToolCallSupport`, `ToolCallExecutionStage`,
  `ToolCallParseStage`, or `ToolCallRepromptStage` extraction.
- No `ExecutionOutcome`, `TurnProcessor`, or `TaskContract` relocation.
- No package-cycle work; the top-level package SCC was already cleared by
  T822.

## Existing Guard Suites

Any T826 production seam must keep these green:

- `ToolCallLoopOrchestrationCharacterizationTest`
- `ToolCallLoopTest`
- `ToolCallLoopP0Test`
- `ToolCallLoopNativeTest`
- `ToolCallLoopCompactionTest`
- `LoopStateTerminalResponseTest`
- `ToolCallSupportTest`
- `ToolCallRepromptStageTest`
- focused `runtime.toolcall.*` repair, evidence, path-policy,
  failure-accounting, and mutation-accounting tests.

## Result

T825 records the remaining `runtime.toolcall` ownership pressure after T824.
The likely T826 decision is between `LoopState` ownership hardening and
`ToolCallExecutionStage` decomposition, with `ToolCallSupport` and
`ToolCallRepromptStage` splits kept as hypotheses until reviewed.
