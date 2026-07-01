# [T461-done-high] Close ToolCallRepromptStage Lane

## Status

Done.

## Scope

T461 reinspects the post-T460 `ToolCallRepromptStage` shape after
`ExpectedTargetScopeRepairPlanner` was extracted.

This is a no-code closeout and next-lane decision ticket. It does not change
runtime behavior, prompt wording, tool selection, verifier behavior, failure
dominance, context-budget behavior, mutation repair semantics, or final
outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `d02ffe87`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` | 944 lines |
| `ToolCallExecutionStage.java` | 1107 lines |
| `StaticWebContinuationPlanner.java` | 511 lines |
| `ExpectedTargetScopeRepairPlanner.java` | 427 lines |
| `TargetReadbackCompactRepairPlanner.java` | 386 lines |
| `CompactMutationContinuationPlanner.java` | 370 lines |
| `SourceEvidenceExactRepairPlanner.java` | 293 lines |
| Architecture baseline | 0 |

## Post-T460 Source Shape

The T445-T460 sequence removed the main planner and deterministic-answer
clusters from `ToolCallRepromptStage` while keeping the stage as the live
tool-loop continuation orchestrator.

`ToolCallRepromptStage` now delegates these closed lanes:

- terminal read-only stop answers to `TerminalReadOnlyStopAnswer`;
- compact read-only evidence continuation to
  `CompactReadOnlyEvidenceContinuation`;
- static-web continuation planning to `StaticWebContinuationPlanner`;
- compact mutation continuation planning to
  `CompactMutationContinuationPlanner`;
- source-evidence exact repair planning to
  `SourceEvidenceExactRepairPlanner`;
- append-line and old-string miss repair planning to
  `TargetReadbackCompactRepairPlanner`;
- expected-target scope repair planning to
  `ExpectedTargetScopeRepairPlanner`.

The stage still owns live lifecycle behavior:

- approval-denial and mutating-denial stop ordering;
- path-policy blocked ordering and expected-target repair dispatch;
- terminal read-only stop placement;
- successful-mutation skip behavior;
- static-web continuation dispatch;
- repair read-only budget handling;
- mutation read-only budget handling;
- failure-policy dominance;
- provider `chatFull(...)` calls for generic continuation;
- context-budget fallback routing;
- transient/provider error wording;
- temporary prompt-frame insertion and cleanup;
- pending action obligation mutation;
- loop-state mutation for `currentText` and `currentNativeCalls`.

## Decision

Close the current `ToolCallRepromptStage` extraction lane.

Do not extract another piece from `ToolCallRepromptStage` merely because the
file is still large. The remaining responsibilities are mostly orchestration
and provider lifecycle. Moving those without a separate design ticket would
mix behavior, ordering, failure dominance, and prompt cleanup in one risky
refactor.

The next hygiene lane should move to `ToolCallExecutionStage`, starting with a
decision/inspection ticket rather than code.

Suggested next ticket:

```text
[T462] ToolCallExecutionStage Policy Pipeline Boundary Decision
```

## Why Not Another Reprompt Extraction

Rejected as immediate T461/T462 implementation work:

- extracting generic `chatReprompt(...)`;
- extracting transient/provider error handling;
- extracting `stopAfterContextBudgetExceeded(...)`;
- extracting only static/expected progress prompt strings;
- extracting remaining target helpers as generic utilities;
- extracting repair read-only budget checks without a larger loop policy
  decision;
- extracting denied-mutation response synthesis as a one-off.

Reasons:

- generic provider calls mutate `LoopState.currentText` and
  `LoopState.currentNativeCalls`;
- context-budget fallback ordering includes pending-action obligations,
  compact mutation continuation, compact read-only evidence continuation, and
  deterministic stop text;
- temporary prompt frames are inserted and removed around one provider call;
- pending action obligations are set immediately before provider controls are
  chosen;
- failure-policy dominance must remain visibly ordered after repair and budget
  paths;
- remaining target helpers are still used by live orchestration, not one
  isolated owner.

## Next Lane Evidence

`ToolCallExecutionStage.java` is now the largest remaining tool-loop policy
class at 1107 lines. It owns execution-time policy and mutation evidence:

- protected-path alias normalization;
- full-rewrite repair edit blocking;
- stale edit reread blocking;
- duplicate failing edit blocking;
- redundant read suppression;
- source-derived write-before-read blocking;
- source-evidence exact coverage repair/blocking;
- append-line preservation blocking;
- private/protected read model-handoff decisions;
- context ledger capture;
- read tracking and mutation tracking;
- denied mutation classification;
- pre-approval path-policy classification;
- unsupported-read tracking;
- mutation evidence extraction;
- static-web full rewrite recovery after edit failures;
- empty-edit and stale-edit failure counters.

That is real policy density. It should be inspected as a pipeline boundary
before implementation because it mixes:

- pre-approval deterministic guards;
- calls into `TurnProcessor.executeTool(...)`;
- model-context content containment;
- workspace operation planning;
- loop-state counters and evidence stores;
- trace capture and action-obligation records;
- user-visible tool-result wording.

## Proposed T462 Questions

T462 should answer:

- Which execution-stage responsibilities are pure pre-execution guards?
- Which checks must stay in `ToolCallExecutionStage` because they need the
  actual `ToolResult`?
- Is there a coherent `PreExecutionToolGuard` or `ToolCallExecutionPolicy`
  owner, or would that hide policy ordering?
- Should source-evidence and append-line pre-approval checks move first, or
  should private/protected read handoff be inspected first?
- Which tests prove approval is not reached for deterministic pre-approval
  denials?
- Which exact wording and trace events must be preserved before any movement?

## Guardrails For The Next Lane

Do not start T462 as an implementation ticket.

T462 must not change:

- approval behavior;
- protected/private document handoff behavior;
- source-evidence repair behavior;
- append-line preservation behavior;
- expected-target scope behavior;
- static-web full rewrite recovery behavior;
- mutation evidence wording;
- context ledger capture;
- final outcome wording.

Implementation should begin only after T462 identifies one coherent owner and
the exact focused tests that will protect it.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
