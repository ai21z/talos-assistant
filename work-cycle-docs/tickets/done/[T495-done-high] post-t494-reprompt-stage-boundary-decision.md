# [T495-done-high] Post-T494 Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T495 reinspects `ToolCallRepromptStage` after T494 extracted
`DeniedMutationResponseOnlySynthesizer`.

This is a no-code decision ticket. It does not change runtime behavior,
outcome wording, repair planning, failure policy, approval behavior, protected
path behavior, context-budget handling, or tool-surface narrowing.

## Current Shape

Source inspection on fresh `origin/v0.9.0-beta-dev` after T494:

| Source | Finding |
| --- | --- |
| `ToolCallRepromptStage.java` | 619 lines |
| `ToolRepromptRequestBuilder` | owns reprompt request assembly and tool narrowing |
| `ToolRepromptContextBudgetHandler` | owns reprompt context-budget fallback paths |
| `StaticWebContinuationPlanner` | owns post-mutation static-web continuation decisions |
| `ExpectedTargetProgressAccounting` | owns expected-target remaining-target accounting |
| `DeniedMutationResponseOnlySynthesizer` | owns non-approval denied-mutation response-only synthesis |

`ToolCallRepromptStage` is no longer a broad warehouse for every reprompt
mechanism, but it is still the live branch-ordering owner. It decides the order
of approval stops, path-policy repair, terminal read-only stops, mutation
continuation, repair/read-only budget stops, generic failure policy, compact
repair planners, transient retry handling, temporary prompt insertion, temporary
prompt cleanup, and final reprompt execution.

That ordering is runtime behavior. It should not be split casually.

## Remaining Responsibility Groups

### Keep In `ToolCallRepromptStage`

These responsibilities are currently orchestration, not independent policy:

- the top-level ordering of terminal stops versus continuation planners;
- selection between static repair obligation and expected-target obligation;
- temporary prompt lifecycle for `[Current task]`, `[Expected target progress]`,
  `[Static repair progress]`, stale-edit repair, and empty-edit repair prompts;
- the actual `chatFull(...)` continuation call and transient retry control flow.

Moving these now would mostly relocate sequencing logic and raise regression
risk without creating a clearer owner.

### Do Not Extract Yet

These areas are real but mixed:

- `repairReadOnlyBudgetExceeded(...)` and `mutationReadOnlyBudgetExceeded(...)`
  mix task-contract interpretation, static-repair context, workspace-operation
  exemptions, compact mutation evidence, conditional review/fix behavior, and
  trace recording.
- `remainingFullRewriteRepairTargets(...)` is tied to static repair context and
  the current pending-obligation order.
- stale-edit and empty-edit repair pass-throughs are already owned by
  `RepairPolicy`; the local methods exist for compatibility with existing
  focused tests.

Do not extract these as line-count cleanup.

## Next Coherent Implementation Slice

The next implementation ticket, if we continue this lane, should be:

```text
[T496] Extract tool failure policy stop answer
```

Rationale:

- `failurePolicyStopMessage(...)` and `failurePolicyRuntimeContext(...)` are
  answer-rendering logic, not reprompt orchestration.
- The rendering has exact wording and truthfulness impact, so it deserves a
  small owner and focused wording tests.
- The extraction can preserve behavior exactly:
  - default reason: `repeated tool failures`;
  - bracketed stop prefix;
  - `Review the latest tool errors before retrying.`;
  - no-progress-only runtime context;
  - task contract line;
  - `mutationAllowed=...`;
  - successful mutation count;
  - read-only contract guidance.
- It should not move `FailurePolicy` decision logic, failure counters,
  repair-budget predicates, transient retry handling, or outcome dominance.

Recommended owner:

```text
dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswer
```

Keeping it in `runtime.toolcall` is intentional for now because the renderer
needs `LoopState`. Moving it to `runtime.failure` would deepen the existing
failure-package dependency on tool-loop state, and moving it to
`runtime.outcome` would mix generic task outcome rendering with live tool-loop
state. A local tool-loop answer renderer is the smallest honest boundary.

## T496 Test Shape

Start with RED tests for `ToolFailurePolicyStopAnswer`:

- blank/null decision reason renders the existing deterministic default message;
- non-no-progress reasons do not append runtime context;
- no-progress reasons append the same runtime context when the task contract is
  known;
- read-only no-progress context preserves the existing guidance line;
- `ToolCallRepromptStage` delegates to `ToolFailurePolicyStopAnswer` and no
  longer owns `failurePolicyRuntimeContext(...)`.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailurePolicyStopAnswerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*failurePolicy*" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Decision

Close the broad reprompt-stage extraction lane after T495 unless T496 is
accepted as the final small answer-rendering cleanup. Do not continue extracting
random internal prompt lifecycle, static repair progress, or repair-budget
predicates from `ToolCallRepromptStage` without a new decision ticket.
