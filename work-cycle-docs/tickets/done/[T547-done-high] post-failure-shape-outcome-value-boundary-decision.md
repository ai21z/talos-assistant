# [T547-done-high] Post Failure Shape Outcome Value Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T547`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `3c0448f1`
Predecessor: `T546`

## Scope

T547 inspects the post-T546 shape of `ToolCallLoop.LoopResult` and
`ToolCallLoop.ToolOutcome` before choosing the next implementation slice.

It intentionally makes no code changes.

## Source Inspection

Commands:

```powershell
rg -l "ToolCallLoop\.LoopResult" src/main/java src/test/java src/e2eTest/java
rg -l "ToolCallLoop\.ToolOutcome" src/main/java src/test/java src/e2eTest/java
rg -n "\.summary\(\)|failure policy stopped|iteration limit reached|Used .*tool|displayFailedCalls|oldStringNotFoundEditFailure|fullRewriteRepairRedirect|invalidEmptyEditArguments" src/main/java src/test/java src/e2eTest/java
rg -n "public (static )?boolean|public boolean|record ToolOutcome|record LoopResult|summary\(|displayFailedCalls|isRecoveredEditFailureShape|normalizeSummaryPath" src/main/java/dev/talos/runtime/ToolCallLoop.java
```

Observed reference counts:

| Surface | Current reference files |
| --- | ---: |
| `ToolCallLoop.LoopResult` | 44 |
| `ToolCallLoop.ToolOutcome` | 78 |

Primary consumers remain broad:

| Area | Evidence |
| --- | --- |
| CLI orchestration | `AssistantTurnExecutor`, `ExecutionOutcome`, read/inspect/mutation retry helpers |
| Runtime outcome rendering | mutation, command, protected-read, unsupported-document, static-verification answer renderers |
| Runtime policy | action/evidence obligation assessment and verification |
| Runtime verification | static verifier, target readback, exact-edit and task-expectation verification |
| Tool-call continuation and repair | compact continuation, expected-target repair, source-evidence repair, static-web continuation |
| E2E harness | scenario result and private-mode scripted harness |
| Tests | large direct construction surface in CLI, runtime outcome, policy, verifier, and tool-call tests |

## Current Ownership Shape

T546 moved known tool failure-shape classification to
`dev.talos.runtime.toolcall.ToolOutcomeFailureShape`.

`ToolCallLoop.ToolOutcome` now mostly behaves as a compatibility data value:

- normalized fields;
- overloaded constructors for older tests and consumers;
- accessor surface used across runtime and CLI;
- compatibility wrapper methods delegating to `ToolOutcomeFailureShape`.

`ToolCallLoop.LoopResult` still carries one coherent behavior cluster:

- `summary()`;
- failed-call display suppression for recovered edit failures;
- iteration-limit marker rendering;
- failure-policy stop marker rendering;
- normalized path comparison for recovered edit failure suppression.

This behavior is not loop orchestration. It is loop-summary formatting.

## Decision

Do not move `ToolCallLoop.ToolOutcome` now.

Reason: it remains a broad compatibility value with 78 reference files across
CLI, runtime outcome rendering, runtime policy, runtime verification, tool-call
repair, E2E harnesses, and tests. A mechanical relocation would be API churn
with high review cost and weak ownership gain.

Do not move `ToolCallLoop.LoopResult` now.

Reason: it remains the public return type of `ToolCallLoop.run(...)` and is
consumed by 44 files. Moving it would touch CLI/runtime integration and large
test construction surfaces without first reducing behavior inside the record.

Do extract the remaining `LoopResult.summary()` formatter next, if continuing
this lane.

Reason: it is a single coherent responsibility, already isolated inside the
record, and can be moved behind the existing `LoopResult.summary()` method
without public API churn.

## Next Implementation Ticket

`T548`: extract `ToolLoopResultSummaryFormatter`.

Target ownership:

```text
dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatter
```

Implementation shape:

1. Add a focused RED test proving loop-result summary formatting has a dedicated
   owner.
2. Move summary-string construction, recovered edit failure suppression, and
   summary path normalization out of `ToolCallLoop.LoopResult`.
3. Keep `LoopResult.summary()` as the public compatibility wrapper.
4. Preserve exact wording:
   - `[Used N tool(s): ... | M iteration(s)]`
   - `[N failed]`
   - `[iteration limit reached]`
   - `[failure policy stopped]`
5. Preserve recovered edit failure suppression behavior.
6. Do not move `ToolOutcome`, `LoopResult`, final-answer rendering, mutation
   outcome rendering, failure policy, or retry policy.

Suggested focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatterTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Standard gates:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Rejected Moves

### Move `ToolOutcome`

Rejected.

It is still too central. The current safer direction is to keep reducing
behavior around the value while preserving its compatibility surface.

### Move `LoopResult`

Rejected.

It remains the public tool-loop return facade. It should not move until the
record is close to a plain transport value or the project deliberately accepts
a compatibility migration.

### Move final-answer or outcome rendering in the same ticket

Rejected.

`LoopResult.summary()` is loop telemetry formatting. Final-answer rendering and
task-outcome rendering have separate ownership and higher truthfulness risk.

## Verification Plan For This Decision Ticket

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
