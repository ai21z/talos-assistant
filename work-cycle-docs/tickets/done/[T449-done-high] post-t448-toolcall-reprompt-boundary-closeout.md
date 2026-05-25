# [T449-done-high] Post-T448 ToolCallRepromptStage Boundary Closeout

## Status

Done.

## Scope

T449 reinspects the post-T448 `ToolCallRepromptStage` shape after
`CompactReadOnlyEvidenceContinuation` was extracted.

This is a no-code decision ticket. It does not change runtime behavior,
outcome wording, tool selection, context-budget handling, or verification
semantics.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `6c393764`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` | 2621 lines |
| `CompactReadOnlyEvidenceContinuation.java` | 188 lines |
| Architecture baseline | 0 |

## Source Inspection

T448 correctly removed the narrow read-only evidence continuation from
`ToolCallRepromptStage`.

The remaining compact mutation continuation is not a small prompt helper:

- `stopAfterContextBudgetExceeded(...)` remains the lifecycle switchboard. It
  records the context-budget skip, gives pending action obligations first
  refusal, tries compact mutation continuation, delegates read-only evidence
  answer synthesis, and finally emits deterministic context-budget stop text.
- `tryCompactMutationContinuation(...)` calls the backend, writes
  `LoopState.currentText`, `LoopState.currentNativeCalls`, and
  `LoopState.failureDecision`, records trace/action-obligation events, and
  decides whether the tool loop continues or stops.
- `compactMutationContinuationForContextBudget(...)` depends on pending action
  obligations, mutation counters, read-only-only progress, task contract
  parsing, workspace-operation exclusion, expected target selection, tool
  narrowing, and provider tool-choice controls.
- `compactMutationContinuationMessages(...)` is mixed with expected targets,
  current readbacks, static-web coherence guidance, source-derived evidence
  readbacks, similar-file traps, and sensitive-path filtering.
- Read-only-overinspection for mutation tasks already routes into compact
  mutation continuation before generic failure policy. Generic failure policy
  remains subordinate and should not be pulled apart casually.

That surface is a coherent runtime behavior, but it is behavior-heavy loop
control. Moving it as a hygiene ticket would create a large behavior-preserving
refactor with high semantic risk and weak payoff.

## Decision

Close the current context-budget continuation extraction lane.

Do not extract compact mutation continuation as T449.

Keep compact mutation continuation inside `ToolCallRepromptStage` for now
because it currently owns live loop progression and failure dominance, not only
message construction.

Do not split out only the compact prompt builder. That would leave the real
ownership problem in place while adding an extra partial abstraction.

## Rejected Next Moves

Rejected for T449:

- extracting `CompactMutationContinuation` immediately;
- extracting only compact mutation prompt text;
- extracting context-budget failure stop wording;
- moving generic `FailurePolicy` dominance from `ToolCallRepromptStage`;
- touching static-web repair, expected-target repair, source-evidence repair,
  old-string compact repair, append-line compact repair, or exact-write
  fallback behavior.

## Next Lane

The next implementation ticket should start a new lane only after source
inspection.

Current best candidate for inspection is terminal read-only stop-answer
ownership, covering methods such as:

- `readTargetStopAnswer(...)`;
- `directoryListingStopAnswer(...)`;
- `unsupportedDocumentStopAnswer(...)`;
- `readOnlyWebDiagnosticStopAnswer(...)`.

This candidate is lower-risk than compact mutation continuation because it
appears to be deterministic answer selection after read-only/tool-policy stop
conditions, but it still needs inspection before code movement.

Suggested next ticket:

```text
[T450] ToolCallRepromptStage terminal read-only stop-answer boundary decision
```

T450 should decide whether those terminal answers form one coherent owner or
whether they should remain local to the reprompt stage.

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
