# [T412-done-high] Execution Outcome Trace Recorder Boundary Decision

## Status

Done.

## Scope

T412 is a no-code inspection and decision ticket.

The goal is to inspect the post-T411 `ExecutionOutcome` shape and choose the
next coherent ownership move. T412 intentionally does not extract code.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `f4112927`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ExecutionOutcome.java` | 826 lines |
| Architecture baseline | 0 |

Already extracted runtime owners:

- `CommandOutcomeRenderer`
- `StaticVerificationAnswerRenderer`
- `TaskOutcomeWarningBuilder`
- `ProtectedReadAnswerGuard`
- `EvidenceContainmentAnswerGuard`
- `MutationOutcome`

## Current Source Shape

`ExecutionOutcome` is now mostly an orchestration facade, but not fully clean.

It still owns these remaining clusters:

1. final orchestration for `fromToolLoop(...)` and `fromNoTool(...)`;
2. status dominance calls through `OutcomeDominancePolicy`;
3. post-apply verifier invocation and embedded static-verification fallback
   parsing;
4. read-only tool-loop-limit answer replacement;
5. evidence-result input adaptation through `evidenceOutcomes(...)`;
6. trace outcome emission through `recordLocalTraceOutcome(...)` and
   `approvalStatus(...)`;
7. compatibility calls into remaining `AssistantTurnExecutor` answer-shaping
   helpers.

The class is no longer one monolithic renderer. The remaining extraction must
be selected carefully.

## Source Evidence

Trace outcome emission is currently local to `ExecutionOutcome`:

```text
recordLocalTraceOutcome(...)
approvalStatus(...)
```

`recordLocalTraceOutcome(...)` does three trace-specific things:

- records the verification result through `LocalTurnTraceCapture.recordVerification(...)`;
- records every `TruthWarning` through `LocalTurnTraceCapture.warning(...)`;
- records the final structured outcome summary through
  `LocalTurnTraceCapture.recordOutcome(...)`.

`approvalStatus(...)` derives a trace-facing approval label from
`TaskOutcome.toolOutcomes()` and `TaskOutcome.mutationOutcome()`.

This logic does not own final answer wording, evidence verification, static
verification, protected-read safety, command rendering, or dominance. It is a
bridge from `TaskOutcome` and `TaskVerificationResult` into local trace state.

The trace subsystem already owns the underlying write API:

```text
LocalTurnTraceCapture.recordVerification(...)
LocalTurnTraceCapture.warning(...)
LocalTurnTraceCapture.recordOutcome(...)
```

Therefore the next ownership move should extract the adapter that records
structured task outcome state into the local trace.

## Decision

The next implementation ticket should be:

```text
[T413] Extract task outcome trace recorder
```

Target class:

```text
dev.talos.runtime.trace.TaskOutcomeTraceRecorder
```

The target package should be `runtime.trace`, not `runtime.outcome`, because the
class performs trace side effects. It may consume runtime outcome data, but it
should not make outcome rendering own trace storage.

## Proposed T413 Boundary

`TaskOutcomeTraceRecorder` should own:

- recording `TaskVerificationResult` into `LocalTurnTraceCapture`;
- recording `TaskOutcome.warnings()` into `LocalTurnTraceCapture`;
- recording final outcome summary fields into `LocalTurnTraceCapture`;
- deriving trace approval status from `TaskOutcome`.

`ExecutionOutcome` should still own:

- when trace recording is called;
- the completion-status and verification-status strings passed to the recorder;
- protocol-sanitized event recording for read-only denied mutation;
- final answer shaping;
- dominance;
- `TaskOutcome` assembly.

Recommended public shape:

```text
TaskOutcomeTraceRecorder.record(
    String completionStatus,
    String verificationStatus,
    TaskOutcome taskOutcome,
    TaskVerificationResult verification
)
```

The method should accept strings for completion and verification status so the
trace recorder does not depend on `ExecutionOutcome.CompletionStatus` or
`ExecutionOutcome.VerificationStatus`.

## Rejected Alternatives

### Extract embedded static-verification parsing next

Rejected for T413.

`embeddedStaticVerificationFailure(...)` and
`embeddedStaticVerificationProblems(...)` are compatibility parsing for answer
text that already contains a static verification failure. That logic is
awkward, but it sits in the middle of action-obligation dominance and static
verification semantics. It should not move until the trace adapter is out and
the remaining verification boundary is inspected directly.

### Extract read-only tool-limit handling next

Rejected for T413.

`READ_ONLY_TOOL_LIMIT_REPLACEMENT` and
`readOnlyToolLimitWithoutRuntimeAnswer(...)` are coherent, but small. Moving
them first would remove less ownership confusion than trace recorder extraction
and would still leave `ExecutionOutcome` writing trace state directly.

### Move `OutcomeDominancePolicy` now

Rejected.

Dominance still consumes `ExecutionOutcome.VerificationStatus` and returns
`ExecutionOutcome.CompletionStatus`. Moving it properly requires a runtime
decision type or status model. That is a larger design step.

### Move all trace-related calls out of `ExecutionOutcome`

Rejected for T413.

The read-only denied mutation protocol-sanitized event is a specific protocol
event, not the structured final outcome summary. T413 should extract only the
task-outcome trace recorder, not every trace side effect in the method.

## T413 Test Shape

Recommended RED/GREEN tests:

- recorder writes verification status, summary, and problems into local trace;
- recorder writes all truth warnings into local trace;
- recorder writes outcome status, verification status, mutation status, and
  task completion classification;
- approval status is `DENIED` when tool outcomes contain denied outcomes;
- approval status is `GRANTED_OR_NOT_REQUIRED` when mutation success count is
  positive;
- approval status is `NONE` when no mutation or denial exists.

Recommended focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.TaskOutcomeTraceRecorderTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Required final gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T412 integrates cleanly, start T413 from fresh
`origin/v0.9.0-beta-dev` and extract only `TaskOutcomeTraceRecorder`.

Do not move embedded static-verification parsing, read-only limit handling, or
dominance policy in the same ticket.
