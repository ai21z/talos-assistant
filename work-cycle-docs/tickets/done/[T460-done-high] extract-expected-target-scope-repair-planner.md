# [T460-done-high] Extract Expected Target Scope Repair Planner

## Status

Done.

## Scope

T460 extracts expected-target scope repair planning from
`ToolCallRepromptStage` into a dedicated runtime/toolcall owner.

This is an ownership refactor. It preserves behavior, prompt wording,
tool selection, required-tool controls, trace wording, pending action
obligations, failure dominance, and final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `325627f0`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` after extraction | 944 lines |
| `ExpectedTargetScopeRepairPlanner.java` | 427 lines |
| `ExpectedTargetScopeRepairPlannerTest.java` | 190 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlanner
```

The planner now owns expected-target scope repair planning:

- wrong-target failure detection and failure-reason parsing;
- remaining expected-target selection for expected-target repair;
- prompted repair key calculation;
- expected-target compact repair messages;
- current expected-target readback framing;
- generated static-web readback framing for successful same-turn small-web mutations;
- missing expected static-web target fallback;
- exact replacement fast-path synthesis for single-target replacement tasks;
- write/edit tool narrowing;
- required-tool controls and debug tags for compact expected-target repair.

`ToolCallRepromptStage` still owns live loop lifecycle:

- deciding where expected-target scope repair sits in the path-policy branch;
- setting `FailureDecision.continueLoop()`;
- setting `PendingActionObligation.expectedTargetScopeTargets(...)`;
- recording prompted expected-target repair keys;
- recording exact replacement repair trace details;
- invoking runtime exact repair or `chatReprompt(...)`;
- preserving failure dominance and final answer shaping.

## Behavior Preserved

Preserved:

- exact `[ExpectedTargetRepair]` prompt marker;
- expected target and failed attempted target wording;
- exact replacement frame wording;
- safe failed-reason wording;
- generated static-web readback wording;
- missing expected static-web file fallback wording;
- `runtime_expected_target_repair` native tool-call id;
- exact repair tool name `talos.edit_file`;
- `expected-target-scope exact replacement target=... after wrong-target block=...` trace detail;
- `pending-action-obligation` debug tag;
- `expected-target-scope-compact-repair` debug tag;
- `expected-target scope compact repair` retry name;
- write/edit tool narrowing;
- already-prompted repair key semantics.

Not changed:

- source-evidence exact repair planning;
- append-line compact repair planning;
- old-string miss compact repair planning;
- static-web continuation planning;
- compact mutation continuation planning;
- generic `chatReprompt(...)` provider lifecycle;
- final answer wording.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: ExpectedTargetScopeRepairPlanner
```

GREEN focused planner verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --no-daemon
```

Focused expected-target scope regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockUsesCompactRepairWithExpectedTargetReadback" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockedMkdirForStaticWebCreationRepromptsToExactFiles" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeRepairIncludesAlreadyWrittenStaticWebReadbacks" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressWrongFileAttemptRepromptsToRemainingStaticWebTarget" --tests "dev.talos.runtime.ToolCallLoopTest.sameIterationExpectedTargetProgressWrongFileRepromptsToRemainingStaticWebTarget" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeRejectsOffTargetWritesBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeRejectsOffTargetEditBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeAllowsExactExpectedTarget" --no-daemon
```

Adjacent source-evidence and target-readback planner regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
```

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.

## Next Move

After T460 is merged and beta push CI is clean, inspect the post-T460
`ToolCallRepromptStage` shape before choosing T461. Do not assume the next
piece is another extraction; expected-target scope, source-evidence exact
repair, target-readback compact repair, and final outcome selection are now
owned outside the stage.
