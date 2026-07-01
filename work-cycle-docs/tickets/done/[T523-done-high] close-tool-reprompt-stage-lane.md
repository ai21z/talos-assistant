# [T523-done-high] Close Tool Reprompt Stage Lane

Status: done
Priority: high
Date: 2026-05-26
Branch: `T523`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `7c636f00`
Predecessor: `T522`

## Scope

T523 is a no-code inspection and closeout ticket for the
`ToolCallRepromptStage` extraction lane.

The task is to inspect the post-T522 shape before choosing another ticket.
This ticket intentionally does not extract another class. The goal is to
decide whether the reprompt stage still contains a concrete ownership problem,
or whether further movement would be line-count chasing.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `7c636f00`:

| File | Lines | Current role |
|---|---:|---|
| `ToolCallRepromptStage.java` | 143 | Ordered reprompt decision orchestrator and remaining obligation selector. |
| `ToolRepromptSuccessfulMutationDecision.java` | 81 | All-success mutation continuation, P0 skip preservation, and static-web continuation handoff. |
| `ToolRepromptPathPolicyBlockedDecision.java` | 52 | Pre-approval path-policy block recovery and fallback stop handling. |
| `ToolRepromptStaleEditRereadStop.java` | 34 | Stale edit reread hard-stop wording, failure decision, and safe logging. |
| `ToolRepromptSourceEvidenceRepairDecision.java` | 25 | Source-evidence exact repair plan invocation and compact retry execution. |
| `ToolRepromptTargetReadbackRepairDecision.java` | 40 | Append-line and old-string-miss target-readback repair plan invocation and compact retry execution. |
| `ToolRepromptOverlayContinuation.java` | 102 | Generic overlay continuation, transient retry, and LLM error handling. |
| `ToolRepromptChatExecutor.java` | 152 | Shared chat execution bridge and response/result handling. |
| `ToolRepromptRequestBuilder.java` | 155 | Reprompt tool specs, message frame, and chat request controls. |
| `ToolRepromptMessageOverlay.java` | 101 | Temporary reprompt message overlays and restoration. |
| `ToolRepromptContextBudgetHandler.java` | 151 | Context-budget fallback and compact evidence continuations. |
| `ToolRepairInspectionBudgetGate.java` | 103 | Read-only repair inspection budget stop decisions. |
| `ToolMutationEvidenceBudgetGate.java` | 50 | Mutation-evidence budget continuation/stop decisions. |
| `TerminalReadOnlyStopAnswer.java` | 232 | Terminal read-only stop-answer selection and wording. |
| `DeniedMutationResponseOnlySynthesizer.java` | 58 | Denied-mutation answer synthesis. |
| `StaticRepairTargetProgressAccounting.java` | 37 | Remaining static repair target accounting. |
| `ExpectedTargetProgressAccounting.java` | 93 | Remaining expected mutation target accounting. |

## Extracted Ownership

The reprompt stage lane now has the following extracted owners:

| Ticket | Extracted owner | Ownership moved out of `ToolCallRepromptStage` |
|---|---|---|
| `T517` | `ToolRepromptSuccessfulMutationDecision` | All-success mutation continuation, static-web pass/continuation checks, P0 successful-mutation skip preservation. |
| `T519` | `ToolRepromptPathPolicyBlockedDecision` | Pre-approval path-policy recovery, expected-target scope repair invocation, exact replacement scheduling, trace repair recording, and fallback stop answer. |
| `T520` | `ToolRepromptStaleEditRereadStop` | Stale-edit reread failure decision, final stop wording, native-call clearing, and safe path logging. |
| `T521` | `ToolRepromptSourceEvidenceRepairDecision` | Source-evidence exact repair plan invocation, pending obligation, prompted key, and compact retry execution. |
| `T522` | `ToolRepromptTargetReadbackRepairDecision` | Append-line and old-string-miss target-readback repair invocation, pending obligation, prompted path key, and compact retry execution. |

Earlier lane work had already extracted or delegated:

- request construction to `ToolRepromptRequestBuilder`;
- temporary prompt overlays to `ToolRepromptMessageOverlay`;
- generic overlay continuation to `ToolRepromptOverlayContinuation`;
- chat execution to `ToolRepromptChatExecutor`;
- context-budget fallbacks to `ToolRepromptContextBudgetHandler`;
- repair inspection budget decisions to `ToolRepairInspectionBudgetGate`;
- mutation-evidence budget decisions to `ToolMutationEvidenceBudgetGate`;
- terminal read-only answers to `TerminalReadOnlyStopAnswer`;
- denied-mutation response text to `DeniedMutationResponseOnlySynthesizer`.

## Current `ToolCallRepromptStage` Role

`ToolCallRepromptStage` is now mostly the ordered reprompt decision chain:

1. stop immediately on explicit approval denial;
2. stop through denied-mutation response synthesis when mutation was denied;
3. delegate path-policy block recovery;
4. delegate stale edit reread hard stop;
5. delegate terminal read-only stop-answer selection;
6. delegate all-success mutation handling;
7. log partial-success fall-through;
8. delegate repair inspection and mutation-evidence budget gates;
9. apply default failure policy;
10. compact older tool results after repeated iterations;
11. delegate source-evidence repair;
12. delegate target-readback repair;
13. compute remaining static-repair and expected-target obligations;
14. enter generic overlay continuation;
15. expose the iteration-limit predicate consumed by `ToolCallLoop`.

That is not perfectly small, but it is no longer the owner of every repair,
retry, prompt-building, budget, trace, and terminal-answer mechanism.

## Remaining Direct Responsibilities

The remaining direct logic is intentionally orchestration-heavy:

- approval-denied terminal stop;
- denied-mutation stop delegation;
- partial-success diagnostic logging;
- default failure-policy stop;
- old tool-result compaction trigger after three iterations;
- remaining static-repair and expected-target obligation selection;
- final `ToolRepromptOverlayContinuation.execute(...)` call;
- `hitIterationLimit(...)`.

The one remaining area that still has some mixed shape is obligation selection:

- `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(...)`;
- `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets(...)`;
- `PendingActionObligation.staticRepairTargets(...)`;
- `PendingActionObligation.expectedTargets(...)`;
- `ToolRepromptRequestBuilder.toolSpecs(...)`.

That code is not large enough to justify extraction by itself today. Moving it
would need a clearer owner, probably an obligation/state-machine ticket, not a
small reprompt-stage helper.

## Rejected Next Extractions

### Extract approval-denied terminal stop

Rejected for now.

Reason: it is four straightforward lines at the top of the ordered chain. It
does not hide a policy algorithm, external dependency, trace side effect, or
retry mechanism.

### Extract partial-success diagnostic fall-through

Rejected for now.

Reason: it is diagnostic logging plus intentional fall-through. Moving it would
create ceremony and make the ordered chain less readable.

### Extract failure-policy stop

Rejected for now.

Reason: `FailurePolicy.defaults(...).afterIteration(...)` is already the policy
owner. The stage only applies the decision and renders the existing stop answer.
An extraction here should wait until failure-policy application needs a broader
owner.

### Extract old tool-result compaction trigger

Rejected for now.

Reason: the trigger is one threshold check before the next model call. A future
conversation-compaction lane may own it, but a small helper now would not
improve the architecture.

### Extract remaining-target obligation selection

Rejected for T523.

Reason: this is the only plausible remaining implementation slice, but it is
not merely a helper. It crosses static-web repair progress, expected-target
progress, pending action obligations, and tool-surface narrowing. If moved, it
should be handled as a deliberate obligation/state-machine ticket with focused
tests, not as the next automatic extraction.

## Decision

Close the `ToolCallRepromptStage` extraction lane for now.

Do not keep extracting from `ToolCallRepromptStage` just because it still has
branches. The current stage has a coherent facade/orchestration role.

The next hygiene step should not be another automatic reprompt-stage burn-down.
The next correct move is a short inspection/decision ticket for the remaining
tool-loop obligation/state-machine boundary.

Recommended next ticket:

```text
[T524] Tool Loop Obligation State Boundary Decision
```

That ticket should inspect:

- `ToolCallRepromptStage`;
- `PendingActionObligation`;
- `StaticRepairTargetProgressAccounting`;
- `ExpectedTargetProgressAccounting`;
- `ToolRepromptRequestBuilder.toolSpecs(...)`;
- `ToolCallLoop` state transitions around reprompting;
- tests covering static repair, expected targets, source evidence, target
  readback, stale rereads, and denied mutations.

T524 should decide whether the next implementation ticket should:

1. extract a `ToolRepromptObligationSelector`;
2. strengthen `PendingActionObligation` as the central state owner;
3. leave obligation selection in the stage until a concrete runtime failure
   requires movement;
4. move to a different hygiene lane.

Do not start T524 by extracting code. The remaining boundary touches repair
progress, expected mutation coverage, and tool-surface narrowing, so a wrong
move can alter runtime behavior even if tests still compile.

## Acceptance Criteria

- The post-T522 reprompt-stage shape is inspected from fresh beta.
- No code changes are made.
- Extracted ownership from T517 through T522 is documented.
- Rejected next extractions are documented.
- The tool-reprompt extraction lane is explicitly closed for now.
- The next ticket is selected as a decision/inspection ticket, not an
  implementation ticket.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 13 executed, 1 up-to-date).
