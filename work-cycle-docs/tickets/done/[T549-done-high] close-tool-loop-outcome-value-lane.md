# [T549-done-high] Close Tool Loop Outcome Value Lane

Status: done
Priority: high
Date: 2026-05-27
Branch: `T549`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `1d293861`
Predecessor: `T548`

## Scope

T549 inspects the post-T548 `ToolCallLoop` nested value shape and decides
whether another immediate implementation extraction is justified.

It intentionally makes no code changes.

## Source Inspection

Commands:

```powershell
rg -n "record LoopResult|record ToolOutcome|public String summary\(|public boolean|ToolLoopResultSummaryFormatter|ToolOutcomeFailureShape|ToolMutationEvidence|LoopResult\(|new LoopResult|new ToolOutcome" src/main/java/dev/talos/runtime/ToolCallLoop.java src/main/java/dev/talos/runtime/toolcall src/main/java/dev/talos/runtime/outcome src/main/java/dev/talos/runtime/policy src/main/java/dev/talos/runtime/verification src/main/java/dev/talos/cli/modes

"LoopResult files: $((rg -l 'ToolCallLoop\.LoopResult' src/main/java src/test/java src/e2eTest/java | Measure-Object).Count)"
"ToolOutcome files: $((rg -l 'ToolCallLoop\.ToolOutcome' src/main/java src/test/java src/e2eTest/java | Measure-Object).Count)"
"Direct constructor lines: $((rg -n 'new ToolCallLoop\.ToolOutcome|new dev\.talos\.runtime\.ToolCallLoop\.ToolOutcome|new ToolCallLoop\.LoopResult|new dev\.talos\.runtime\.ToolCallLoop\.LoopResult' src/main/java src/test/java src/e2eTest/java | Measure-Object).Count)"
```

Observed counts:

| Surface | Current count |
| --- | ---: |
| Files referencing `ToolCallLoop.LoopResult` | 46 |
| Files referencing `ToolCallLoop.ToolOutcome` | 80 |
| Direct constructor reference lines | 316 |

The counts include the new formatter/test ownership added by T548, but they
still show the important fact: these records are broad compatibility surfaces.

## Current Shape

`ToolCallLoop.LoopResult` now contains:

- field normalization in the compact constructor;
- overloads for compatibility with older tests and call sites;
- `summary()` as a compatibility wrapper delegating to
  `ToolLoopResultSummaryFormatter`.

`ToolCallLoop.ToolOutcome` now contains:

- field normalization in the compact constructor;
- overloads for compatibility with older tests and call sites;
- `ToolMutationEvidence` attachment;
- failure-shape wrapper methods delegating to `ToolOutcomeFailureShape`.

The remaining logic in these records is now mostly compatibility and value
normalization. The obvious behavior clusters have already moved out:

| Moved owner | Responsibility |
| --- | --- |
| `ToolMutationEvidence` | mutation proof value |
| `ToolOutcomeFailureShape` | known failure-shape classification |
| `ToolLoopResultSummaryFormatter` | loop telemetry summary formatting |

## Decision

Close the tool-loop outcome value lane for now.

Do not move `ToolCallLoop.LoopResult` in the next ticket.

Do not move `ToolCallLoop.ToolOutcome` in the next ticket.

Reason: the remaining work is not a local extraction. It is a compatibility
migration touching CLI orchestration, runtime outcome rendering, runtime policy,
runtime verification, tool-call repair, E2E harnesses, and a large direct test
construction surface.

Moving either record now would be noisy churn with weak architectural gain.
The correct move is to preserve the compatibility surface until a specific
future problem requires a deliberate migration plan.

## Rejected Next Tickets

### Move `LoopResult`

Rejected.

It remains the public return type of `ToolCallLoop.run(...)` and still has 46
reference files. A move would force broad CLI/runtime/test changes without
removing meaningful behavior.

### Move `ToolOutcome`

Rejected.

It remains a central per-tool result value with 80 reference files and many
direct constructor call sites. A move needs a compatibility strategy, not a
routine extraction ticket.

### Extract another tiny wrapper from the records

Rejected.

The remaining methods are compatibility constructors, normalization, and
delegation wrappers. Extracting more would produce indirection without a real
ownership payoff.

### Rewrite tests around new builders now

Rejected.

Test construction noise is real, but broad test-fixture churn does not improve
runtime architecture enough to justify doing it in the same hygiene lane.

## What This Lane Achieved

This lane reduced `ToolCallLoop` by moving real behavior out while keeping the
public API stable:

- final answer shaping moved to `ToolLoopFinalAnswerFinalizer`;
- terminal response helpers moved into `LoopState`;
- compact mutation continuation moved to `CompactMutationContinuationExecutor`;
- mutation evidence moved to `ToolMutationEvidence`;
- failure-shape classification moved to `ToolOutcomeFailureShape`;
- loop summary formatting moved to `ToolLoopResultSummaryFormatter`.

The remaining nested records are acceptable beta compatibility surfaces.

## Next Move

Stop this lane and plan the next hygiene lane from current source.

Good candidates for the next planning ticket:

1. Runtime/CLI boundary review for `AssistantTurnExecutor` after the tool-loop
   extractions.
2. Trace and artifact evidence ownership review.
3. Test-fixture construction hygiene, if the team wants to reduce constructor
   churn before a larger value migration.

Do not start an implementation ticket by default. The next ticket should be a
decision/inventory ticket unless there is already a specific, source-proven
owner to extract.

## Verification Plan For This Decision Ticket

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
