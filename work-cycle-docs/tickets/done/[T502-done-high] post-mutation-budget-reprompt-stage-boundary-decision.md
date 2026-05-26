# [T502-done-high] Post-Mutation-Budget Reprompt Stage Boundary Decision

## Status

Done.

## Scope

T502 inspects `ToolCallRepromptStage` after T499 and T501 extracted the
repair/fix inspection budget gate and mutation evidence budget gate.

This is a no-code decision ticket. It does not change runtime behavior,
reprompt ordering, prompt wording, repair wording, compact continuation,
approval handling, failure policy, trace behavior, protected-path behavior, or
verification behavior.

## Current Shape

Fresh `origin/v0.9.0-beta-dev` after T501:

- `ToolCallRepromptStage` is 533 lines.
- Budget gates are now delegated:
  - `ToolRepairInspectionBudgetGate.tryStop(...)`;
  - `ToolMutationEvidenceBudgetGate.tryContinueOrStop(...)`.
- Compact continuation planning/execution remains outside the stage:
  - `ToolRepromptContextBudgetHandler`;
  - `CompactMutationContinuationPlanner`.
- Static web continuation, expected-target progress, source-evidence repair,
  target-readback compact repair, and terminal read-only answers already have
  named owners.

Remaining direct responsibilities in `ToolCallRepromptStage`:

| Responsibility | Current owner evidence | Decision |
| --- | --- | --- |
| high-level branch ordering | `reprompt(...)` | keep in stage |
| approval-denied terminal answers | top of `reprompt(...)` | keep for now; adjacent to execution outcome |
| path-policy blocked target-scope repair | `ExpectedTargetScopeRepairPlanner.nextPlan(...)` branch | keep ordering in stage |
| stale edit retry stop | direct `staleEditRereadIgnoredPath` branch | inspect later; failure-policy adjacent |
| post-mutation skip/continuation decision | mutation-success branch | inspect later as one coherent post-mutation decision owner |
| source evidence exact repair | `SourceEvidenceExactRepairPlanner` branch | already delegated enough |
| target readback repair | `TargetReadbackCompactRepairPlanner` branch | already delegated enough |
| temporary repair/progress/anchor message overlay and cleanup | inline index variables and `finally` cleanup | coherent but behavior-sensitive; inspect before moving |
| chat reprompt execution and engine-error handling | `chatReprompt(...)`, `chatRepromptResult(...)`, transient retry block | coherent but behavior-sensitive; not first |
| stale/empty edit repair lookup wrappers | `nextStaleEditRepair(...)`, `nextEmptyEditRepair(...)`, instruction wrappers | should move out of stage API now |
| remaining full rewrite target calculation | `remainingFullRewriteRepairTargets(...)` | inspect later with post-mutation continuation |

## Decision

Do not extract compact continuation, generic chat execution, or temporary
message overlay next.

The next implementation ticket should remove a small but real ownership leak:
`ToolCallRepromptStage` exposes stale/empty edit repair lookup and instruction
wrappers that simply delegate to `RepairPolicy`.

Those wrappers make `ToolCallRepromptStage` look like the owner of repair
instruction policy even though `RepairPolicy` is already the true owner and is
already tested directly.

## Next Coherent Implementation Slice

The next implementation ticket should be:

```text
[T503] Remove repair policy wrappers from reprompt stage
```

Scope:

- Update `ToolCallRepromptStage` to call `RepairPolicy.nextStaleEditRepair(...)`
  and `RepairPolicy.nextEmptyEditRepair(...)` directly.
- Delete these wrapper methods from `ToolCallRepromptStage`:
  - `nextStaleEditRepair(...)`;
  - `staleEditRepairInstruction(...)`;
  - `nextEmptyEditRepair(...)`;
  - `emptyEditRepairInstruction(...)`.
- Move or update wrapper-dependent tests so stale/empty edit repair policy is
  asserted against `RepairPolicy`, not the reprompt stage.
- Preserve exact repair instruction wording and one-shot behavior.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*empty*" --tests "dev.talos.runtime.ToolCallLoopTest.*stale*" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result: all passed.

## Do Not Touch In T503

T503 must not move:

- compact mutation continuation;
- `ToolRepromptContextBudgetHandler`;
- `CompactMutationContinuationPlanner`;
- temporary prompt overlay and cleanup;
- post-mutation continuation selection;
- stale edit retry failure-policy stop;
- source-evidence or target-readback compact repairs.

## Later Inspection

After T503, inspect whether the next coherent owner is:

- post-mutation continuation/skip decision;
- temporary reprompt message overlay and cleanup;
- generic chat reprompt execution/error handling.

Do not choose among those without source inspection because they affect prompt
shape, error wording, cleanup guarantees, and failure truthfulness.
