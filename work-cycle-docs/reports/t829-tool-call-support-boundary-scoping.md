# T829 ToolCallSupport Boundary Scoping

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- T828 implementation commit:
  `4d45b3ed54b50bdf75ceb457b298a572a0783d7a`
- T828 closeout commit:
  `de8e3d066a2e5bf10179f4cfb60e0b9212f72898`
- Talos version: `0.10.5`
- Generated architecture report:
  `build/reports/talos/architecture-intelligence/current/data/wave5-sequence-recommendations.json`
- Report commit: `de8e3d066a2e5bf10179f4cfb60e0b9212f72898`
- Confidence label: `INFERRED_REVIEW`
- Ticket:
  `work-cycle-docs/tickets/open/[T829-open-high] tool-call-support-boundary-scoping.md`

T829 is scoping/characterization only. T829 does not authorize production
extraction.

## Current Hotspot Evidence

The generated priority index is a review-order heuristic, not a success metric.

| Candidate | Priority index | Confidence | T829 meaning |
|---|---:|---|---|
| `runtime.toolcall.LoopState` | `293` | `INFERRED_REVIEW` | Higher-ranked internal, deferred because direct construction blast radius is broad. |
| `runtime.toolcall.ToolCallSupport` | `236` | `INFERRED_REVIEW` | Broad static helper surface selected for boundary scoping. |
| `runtime.toolcall.ToolCallExecutionStage` | `119` | `INFERRED_REVIEW` | T828 reduced hotspot pressure; public stage surface remains stable. |
| `cli.modes.ExecutionOutcome` | `99` | `INFERRED_REVIEW` | Large file, but lower generated priority than tool-loop internals. |

## Candidate T830 Seam Hypotheses

T829 validates these as hypotheses only. T830 must choose one seam after this
report and the characterization tests are reviewed.

| Hypothesis | Current members | T830 caution |
|---|---|---|
| Native-call conversion | `convertNativeToolCalls(...)` and native argument JSON/scalar rendering. | Preserve legacy scalar stringification and JSON serialization for container arguments. |
| Tool-result formatting | `formatToolResult(...)`, verification summary extraction, protected-content sanitization, truncation, and first-sentence summaries. | Preserve prompt-visible text shape and protected content redaction. |
| Retry/request extraction | `latestUserRequestIn(...)`, embedded retry task/user-request helpers, synthetic tool-result detection. | Validate this is a real ownership seam before moving; it may belong with retry policy rather than tool-call support. |
| Path/call repair | read-path canonicalization, call signatures, missing-path preservation, path hint extraction, mutation/read-only name checks. | Preserve alias-policy delegation and fail-closed behavior for unknown names. |
| Compaction | `summarizeToolResult(...)` and `compactOlderToolResultsInPlace(...)`. | Preserve tool-call IDs and keep the most recent tool results verbatim. |

## Do Not Move In T829

- No production source.
- No `ToolCallLoop` public static delegate changes.
- No `LoopState`, `ToolCallExecutionStage`, `ToolCallPreExecutionGuardChain`,
  `ToolCallParseStage`, `ToolCallRepromptStage`, or `ExecutionOutcome` move.
- No package-cycle work.

## Existing Guard Suites

Any T830 production seam must keep these green:

- `ToolCallSupportBoundaryCharacterizationTest`
- `ToolCallSupportTest`
- `ToolCallLoopCompactionTest`
- `NativeToolPipelineTest`
- `runtime.toolcall.*`
- `ToolCallLoop*`

## Result

T829 records the `ToolCallSupport` boundary and candidate split hypotheses.
The strongest initial T830 candidates are result formatting or native-call
conversion because they are narrow and already have direct helper-level tests.
Retry-message utilities remain a hypothesis, not a decided extraction boundary.
