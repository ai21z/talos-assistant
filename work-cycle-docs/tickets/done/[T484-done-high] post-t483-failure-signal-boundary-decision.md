# [T484-done-high] Post-T483 Failure Signal Boundary Decision

## Status

Done.

## Scope

T484 inspects the post-T483 `ToolCallExecutionStage` shape and decides whether
the next ticket should continue extracting from the stage, close the current
lane, or shift to another ownership lane. This is a no-code decision ticket.

It does not change runtime behavior, approval behavior, protected/private
handoff behavior, context-ledger behavior, read evidence accounting, mutation
accounting, mutation evidence construction, failure classification, generic
failure state accounting, edit-repair behavior, static-web repair behavior,
trace wording, prompt wording, outcome wording, or final answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `c60b540f`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 502 lines |
| Architecture baseline | 0 |

## Source Evidence

After T483, `ToolCallExecutionStage` is much closer to orchestration, but it
still directly translates `ToolExecutionFailureClassifier.Classification` into
iteration-level signals:

```text
mutatingDeniedThisIter
unsupportedReadPathsThisIter
pathPolicyBlockedThisIter
state.failureDecision for expected-target scope block
approvalDeniedThisIter
```

Current source:

- classification is created at `ToolCallExecutionStage.java` lines 358-359;
- mutating denied flag is set at lines 360-362;
- unsupported read paths are collected at lines 363-365;
- path-policy and expected-target failure decision are set at lines 366-374;
- approval denial is set at lines 375-378.

This logic is not failure classification itself anymore. T479 already extracted
that. It is also not generic failure accounting or edit-repair accounting. It
is the adapter that turns a failed tool result classification into the
iteration signals consumed by `ToolCallRepromptStage`.

## Decision

The next correct implementation ticket is:

```text
[T485] Extract tool failure iteration signals
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolFailureIterationSignals
```

Preferred responsibilities:

- consume `ToolExecutionFailureClassifier.Classification`;
- report whether this iteration saw a mutating denial;
- report whether this iteration saw an approval denial;
- report whether this iteration saw a pre-approval path-policy block;
- report unsupported read paths as immutable signal data;
- set `state.failureDecision` for expected-target scope blocks using the
  existing `FailureDecision.stop(FailureAction.ASK_USER, result.errorMessage())`
  behavior;
- preserve exact signal semantics and failure-decision wording.

`ToolCallExecutionStage` should keep:

- when classification is requested;
- composing iteration-local booleans and lists;
- `ToolOutcome` construction;
- generic failure accounting;
- edit failure repair accounting;
- tool-result message formatting;
- overall iteration outcome assembly.

## Why This Slice Is Correct

The failure signal adapter is a coherent boundary between two already-extracted
owners:

- `ToolExecutionFailureClassifier` decides what kind of failed result occurred;
- `ToolCallRepromptStage` later acts on iteration signals.

Keeping signal interpretation directly inside the execution stage forces the
stage to understand every failure category even after classification has moved.
Extracting the signal adapter removes that ownership confusion without moving
tool execution, result formatting, prompt wording, repair prompts, or outcome
recording.

## Rejected Immediate Work

### Extract tool outcome construction

Rejected for T485.

`ToolOutcome` construction spans synthetic pre-execution failures, executed
tool results, mutation evidence, workspace operation plans, summaries, and
error codes. It is a real remaining owner candidate, but it has more behavior
surface than the failure signal adapter.

### Extract pre-execution policy block handling

Rejected for T485.

Source-derived evidence and append-line preservation blocks include diagnostic
formatting, action-obligation trace records, synthetic failed tool outcomes,
and optional source-evidence repair. That boundary needs its own inspection
ticket before implementation.

### Close the execution-stage lane immediately

Rejected for now.

The stage still has a small, clear non-orchestration pocket: failure iteration
signals. Removing that pocket is low risk and improves the stage before the
remaining larger decisions.

## Required T485 Tests

Start with RED tests for `ToolFailureIterationSignals`:

- mutating denied classification reports `mutatingDenied=true`;
- user approval denial reports `approvalDenied=true`;
- unsupported read-file classification returns the normalized unsupported read
  path;
- expected-target scope block reports `pathPolicyBlocked=true` and sets
  `state.failureDecision` with the existing `ASK_USER` action and exact error
  message;
- non-mutating or successful/non-failed classifications produce no signals;
- `ToolCallExecutionStage` delegates failure signal interpretation and no
  longer owns direct `failureClassification.mutatingDenied()`,
  `failureClassification.unsupportedReadPath()`,
  `failureClassification.preApprovalPathPolicyBlock()`, or
  `failureClassification.userApprovalDenial()` checks.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolFailureIterationSignalsTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*approval*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --tests "dev.talos.runtime.ToolCallLoopTest.*unsupported*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
