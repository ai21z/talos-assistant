# [T459-done-high] Extract Target Readback Compact Repair Planner

## Status

Done.

## Scope

T459 implements the post-T458 inspection decision: extract target-readback
compact repair planning from `ToolCallRepromptStage` without moving the
expected-target scope repair path.

This is an ownership refactor. It preserves runtime behavior, prompt wording,
tool narrowing, required-tool controls, pending action obligations, failure
dominance, and final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `aecdd6fd`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` after extraction | 1349 lines |
| `TargetReadbackCompactRepairPlanner.java` | 414 lines |
| `TargetReadbackCompactRepairPlannerTest.java` | 206 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlanner
```

The planner now owns target-readback compact repair planning for:

- append-line preservation failures;
- old-string miss edit failures;
- remaining expected-target filtering for those two repair kinds;
- same-turn readback lookup for compact repair;
- append-line expectation selection;
- prompt frame construction for `[AppendLineRepair]`;
- prompt frame construction for `[OldStringMissRepair]`;
- write/edit tool narrowing for those compact repairs;
- required-tool controls and repair debug tags.

`ToolCallRepromptStage` still owns live loop lifecycle:

- deciding where target-readback repair sits in the reprompt order;
- setting `PendingActionObligation.appendLineTargets(...)`;
- setting `PendingActionObligation.oldStringMissTargets(...)`;
- recording prompted append-line and old-string repair path keys;
- invoking `chatReprompt(...)`;
- preserving failure dominance and final answer shaping.

## Deliberately Not Moved

Expected-target scope repair remains in `ToolCallRepromptStage`.

Reason: that path still mixes pre-approval path-policy failure handling,
failure-reason parsing, static-web readbacks from disk, exact replacement
repair call synthesis, missing-file creation fallback, and path-scope wording.
Moving it in T459 would be a larger ownership decision than the target-readback
compact repair slice.

The stage now reuses the planner's readback lookup helper for expected-target
scope repair, but the expected-target scope repair planner itself was not moved.

## Behavior Preserved

Preserved:

- exact `[AppendLineRepair]` prompt marker;
- exact `[OldStringMissRepair]` prompt marker;
- append-line required line wording;
- old-string miss failed-reason wording;
- compact readback truncation behavior;
- `pending-action-obligation` debug tag;
- `append-line-compact-repair` debug tag;
- `old-string-miss-compact-repair` debug tag;
- `append-line compact repair` retry name;
- `old-string miss compact repair` retry name;
- write/edit tool narrowing;
- case-preserving target display;
- stale-readback protection after same-turn mutation;
- no-tool/read-only repair failure behavior.

Not changed:

- source-evidence exact repair planning;
- expected-target scope repair planning;
- static-web continuation planning;
- compact mutation continuation planning;
- generic `chatReprompt(...)` provider lifecycle;
- final answer wording.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: TargetReadbackCompactRepairPlanner
```

GREEN focused planner verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
```

Focused append-line and old-string regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairDoesNotUseReadbackFromBeforeSuccessfulMutation" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairPreservesExpectedTargetCasing" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairNoToolProseBecomesDeterministicFailure" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairRejectsReadOnlyToolBeforeExecution" --no-daemon
```

Neighboring expected-target scope regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockUsesCompactRepairWithExpectedTargetReadback" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockedMkdirForStaticWebCreationRepromptsToExactFiles" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeRepairIncludesAlreadyWrittenStaticWebReadbacks" --no-daemon
```

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.

## Next Move

After T459 is merged and beta push CI is clean, inspect the post-T459
`ToolCallRepromptStage` shape before choosing T460. Expected-target scope
repair is now the obvious candidate, but it should still begin with source
inspection because it crosses path-policy, static-web, exact replacement, and
missing-file fallback behavior.
