# [T457-done-high] Post-T456 ToolCallRepromptStage Boundary Decision

## Status

Done.

## Scope

T457 reinspects the post-T456 `ToolCallRepromptStage` shape after
`CompactMutationContinuationPlanner` was extracted.

This is a no-code decision ticket. It does not change runtime behavior,
prompt wording, tool selection, verifier behavior, failure dominance,
context-budget behavior, mutation repair semantics, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `ab5d3fe6`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` | 1709 lines |
| `CompactMutationContinuationPlanner.java` | 407 lines |
| Architecture baseline | 0 |

## Post-T456 Source Shape

T456 correctly removed compact mutation continuation planning from
`ToolCallRepromptStage` while keeping live loop lifecycle in the stage.

`ToolCallRepromptStage` now delegates these already-closed lanes:

- terminal read-only stop answers to `TerminalReadOnlyStopAnswer`;
- compact read-only evidence answers to `CompactReadOnlyEvidenceContinuation`;
- static-web continuation planning to `StaticWebContinuationPlanner`;
- compact mutation continuation planning to
  `CompactMutationContinuationPlanner`.

The remaining stage-owned repair clusters are:

| Cluster | Source | Finding |
|---|---|---|
| Expected-target scope repair | `nextExpectedTargetScopeRepair(...)`, `expectedTargetsFromScopeFailureReason(...)`, `expectedTargetRepair(...)`, `appendSuccessfulStaticWebMutationReadbacks(...)`, `exactExpectedTargetReplacementRepairCall(...)` | Coherent but high-coupling. It mixes pre-approval path-policy failure parsing, expected-target fallback recovery from failure strings, static-web generated file readbacks, exact replacement repair calls, pending repair keys, and remaining target calculation. |
| Source-evidence exact repair | `nextSourceEvidenceExactRepair(...)`, `sourceEvidenceExactRepairToolSpecs(...)`, `sourceEvidenceExactRepairMessages(...)`, `sourceEvidenceExactRepairKey(...)` | Best next implementation owner. It is narrower: a failed source-derived write is repaired by a compact write-only plan with exact source-evidence phrases from same-turn readbacks. |
| Append-line compact repair | `nextAppendLineCompactRepair(...)`, `appendLineExpectationForPath(...)`, `appendLineRepairMessages(...)` | Coherent but tied to append-line expectation semantics and same-turn readback preservation. Keep for later. |
| Old-string miss compact repair | `nextOldStringMissCompactRepair(...)`, `oldStringMissRepairMessages(...)`, target casing preservation, stale-readback interaction | Coherent and well-covered, but it should follow source-evidence repair because it has broader edit/write fallback semantics and more failure-dominance tests. |
| Shared repair helpers | `remainingExpectedMutationTargets(...)`, `successfulReadbackForPath(...)`, `latestSuccessfulReadbackForPath(...)`, `truncateForCompactRepair(...)`, `oldStringMissRepairToolSpecs(...)` | Do not extract generically first. These helpers serve multiple repair lanes and would become a vague utility package if moved before owners are split. |

## Decision

The next implementation ticket should be:

```text
[T458] Extract source evidence exact repair planner
```

Target owner:

```text
dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlanner
```

Preferred shape:

```text
SourceEvidenceExactRepairPlanner.nextPlan(
    LoopState state,
    List<ToolSpec> baseTools,
    String userTask
)
```

The returned plan should contain:

- target path;
- repair key;
- request messages;
- narrowed repair tools;
- `ChatRequestControls`;
- source readback evidence needed by the compact repair.

`ToolCallRepromptStage` should keep lifecycle placement:

- decide when source-evidence exact repair is considered;
- set `PendingActionObligation.expectedTargets(...)`;
- record the prompted repair key;
- invoke the existing `chatReprompt(...)`;
- preserve ordering relative to failure policy, append-line repair,
  old-string repair, stale-edit repair, and generic reprompt.

## Why Source-Evidence Repair First

This is the smallest remaining owner that is still real architecture:

- it has one trigger: a failed mutating outcome whose message contains
  `Source-derived write blocked before approval`;
- it has one policy purpose: force exact source evidence phrases into a
  source-derived output before retrying the write;
- it already relies on `SourceDerivedEvidenceGuard.sourceReadbacks(...)`;
- it does not need direct filesystem reads;
- it does not need static-web generated-file readbacks;
- it does not need exact replacement native-call planning;
- it can stay plan-returning, like the T454 and T456 extractions.

## Rejected T458 Alternatives

### Extract expected-target scope repair first

Rejected for the next ticket.

Expected-target scope repair is important, but it crosses too many concerns at
once:

- pre-approval path-policy failure parsing;
- remaining expected-target calculation;
- recovery from failure-reason text when tool outcomes are insufficient;
- static-web generated file readbacks from disk;
- exact replacement repair native call construction;
- path casing and similar-target behavior;
- pending expected-target scope repair keys.

It should get its own decision or implementation ticket after the narrower
source-evidence repair owner is separated.

### Extract append-line repair first

Rejected for the next ticket.

Append-line repair has a clear owner, but its correctness depends on
append-line expectation parsing and preserving same-turn readback semantics.
It should not be mixed with source-derived evidence ownership.

### Extract old-string miss repair first

Rejected for the next ticket.

Old-string miss repair is well covered, but it owns edit/write fallback
semantics, target casing, stale-readback interaction, and no-tool deterministic
failure behavior. It is a later coherent lane, not the immediate next slice.

### Extract shared repair helpers first

Rejected.

Moving `remainingExpectedMutationTargets(...)`,
`latestSuccessfulReadbackForPath(...)`, or tool-spec helpers before extracting
concrete owners would create a generic repair utility without clear policy
ownership.

## T458 Guardrails

T458 must preserve:

- exact `[SourceEvidenceExactRepair]` prompt wording;
- exact failed-reason wording in the compact repair frame;
- exact source evidence phrase selection through
  `SourceDerivedEvidenceGuard.evidenceSnippet(...)`;
- `source-evidence-exact-compact-repair` debug tag;
- `source-evidence exact compact repair` retry name;
- write-file-only narrowing when available;
- fallback to the existing write/edit repair tools when write-file narrowing is
  unavailable;
- write-file schema enum for the repaired target;
- schema description containing required exact source evidence phrases;
- repair key semantics;
- pending expected-target obligation setup in `ToolCallRepromptStage`;
- no extra model retry when deterministic source-evidence repair already
  succeeds before approval;
- no behavior change for append-line, old-string miss, expected-target scope,
  static-web continuation, compact mutation continuation, or generic reprompt.

`ToolCallRepromptStage` must still own:

- lifecycle placement;
- pending action obligation mutation;
- prompted-key mutation;
- provider call through `chatReprompt(...)`;
- failure dominance and final answer shaping.

## Proposed T458 Test Plan

Start with a RED planner ownership test for:

- source-evidence exact repair plan detection from a failed source-derived
  write;
- target path and repair key preservation;
- exact evidence phrase inclusion in the prompt and schema;
- write-file-only tool narrowing and schema rewrite;
- stale prior conversation exclusion from the compact prompt;
- no plan when the failed write is not for a remaining expected target.

Focused regression candidates:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite" --no-daemon
```

Adjacent repair regressions:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockUsesCompactRepairWithExpectedTargetReadback" --tests "dev.talos.runtime.ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure" --no-daemon
```

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
