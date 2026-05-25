# [T458-done-high] Extract Source Evidence Exact Repair Planner

## Status

Done.

## Scope

T458 implements the T457 decision: extract source-evidence exact repair
planning from `ToolCallRepromptStage` into a plan-returning runtime/toolcall
owner.

This is an ownership refactor. It preserves runtime behavior, prompt wording,
tool selection, required-tool controls, pending action obligations, failure
dominance, and final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `cffcf0ae`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` after extraction | 1562 lines |
| `SourceEvidenceExactRepairPlanner.java` | 315 lines |
| `SourceEvidenceExactRepairPlannerTest.java` | 197 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlanner
```

The planner now owns source-evidence exact repair planning:

- source-evidence exact repair eligibility;
- source readback collection through `SourceDerivedEvidenceGuard`;
- remaining expected target scoping;
- prompted repair key calculation;
- compact source-evidence repair messages;
- exact evidence phrase selection;
- write-file-only tool narrowing;
- write-file schema rewrite for the repaired target;
- fallback repair tool narrowing when write-file is unavailable;
- required-tool controls for the compact repair.

`ToolCallRepromptStage` still owns live loop lifecycle:

- deciding where source-evidence repair sits in the reprompt order;
- setting `PendingActionObligation.expectedTargets(...)`;
- recording prompted source-evidence repair keys;
- invoking `chatReprompt(...)`;
- preserving failure dominance and final answer shaping.

## Behavior Preserved

Preserved:

- exact `[SourceEvidenceExactRepair]` prompt marker;
- exact failed-reason inclusion in the compact repair frame;
- exact source-evidence phrase selection through
  `SourceDerivedEvidenceGuard.evidenceSnippet(...)`;
- `pending-action-obligation` and `source-evidence-exact-compact-repair`
  debug tags;
- `source-evidence exact compact repair` retry name;
- write-file-only narrowing when available;
- fallback write/edit repair tools when write-file narrowing is unavailable;
- target enum schema for the repaired path;
- schema description requiring exact source evidence phrases;
- source-evidence repair key semantics;
- pending expected-target obligation setup in `ToolCallRepromptStage`.

Not changed:

- deterministic pre-approval source-evidence repair;
- expected-target scope repair;
- append-line compact repair;
- old-string miss compact repair;
- static-web continuation planning;
- compact mutation continuation planning;
- generic `chatReprompt(...)` provider lifecycle;
- final answer wording.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: SourceEvidenceExactRepairPlanner
```

GREEN focused planner verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
```

Focused source-evidence regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.sourceDerivedExactEvidenceWriteMissingSourcePhraseIsRepairedBeforeMutation" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite" --no-daemon
```

Adjacent repair regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockUsesCompactRepairWithExpectedTargetReadback" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure" --no-daemon
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

After T458 is merged and beta push CI is clean, inspect the post-T458
`ToolCallRepromptStage` shape before choosing T459. The likely next candidate
is one of the target-only repair planners, but expected-target scope repair
should not be assumed without source inspection because it still crosses
path-policy and static-web behavior.
