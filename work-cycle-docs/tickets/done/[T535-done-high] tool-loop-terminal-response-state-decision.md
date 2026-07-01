# [T535-done-high] Tool Loop Terminal Response State Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T535`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `3c57d81e`
Predecessor: `T534`

## Scope

T535 is a no-code decision ticket for the response-state cluster identified in
T534.

The question is whether `LoopState.currentText`,
`LoopState.currentNativeCalls`, and `LoopState.failureDecision` now have a
coherent implementation slice, or whether moving them would blur terminal
answers, retry setup, failure decisions, and compact continuations.

## Source Evidence

Inspected from fresh `origin/v0.9.0-beta-dev` at `3c57d81e`.

Primary files:

| File | Evidence |
|---|---|
| `src/main/java/dev/talos/runtime/toolcall/LoopState.java` | Owns mutable response fields, pending-obligation failures, static repair failures, and direct terminal failure application. |
| `src/main/java/dev/talos/runtime/ToolCallLoop.java` | Parses `state.currentText/currentNativeCalls`, applies unfinished-continuation and iteration-limit fallback, finalizes the answer into `LoopResult`. |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | Applies denied-mutation terminal answers, terminal read-only answers, and failure-policy terminal answers. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptChatExecutor.java` | Applies normal reprompt results, empty-result fallbacks, and model/engine error terminal answers. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java` | Applies overlay continuation results and duplicate model/engine error terminal answers. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptContextBudgetHandler.java` | Applies context-budget failures, compact mutation continuation, and compact no-tool terminal failure. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptSuccessfulMutationDecision.java` | Applies successful-mutation terminal summaries. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepairInspectionBudgetGate.java` | Applies terminal repair-inspection failure and conditional no-change terminal answer. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptPathPolicyBlockedDecision.java` | Applies expected-target repair setup and terminal path-policy blocked answer. |
| `src/main/java/dev/talos/runtime/toolcall/ToolRepromptStaleEditRereadStop.java` | Applies terminal stale-edit failure. |
| `src/main/java/dev/talos/runtime/toolcall/CompactReadOnlyEvidenceContinuation.java` | Applies compact read-only evidence answer and clears pending obligation. |

The assignment inventory was collected with:

```powershell
rg -n "state\.currentText\s*=|state\.currentNativeCalls\s*=|state\.failureDecision\s*=" src/main/java/dev/talos/runtime src/test/java/dev/talos/runtime
```

## Assignment Classification

| Bucket | Representative assignments | Classification |
|---|---|---|
| Terminal failure stop | `state.failureDecision = FailureDecision.stop(...)`, `state.currentText = ...`, `state.currentNativeCalls = List.of()` | Coherent. This is a good candidate for a small `LoopState` method because the three-field mutation means "stop with failure answer". |
| Terminal non-failure stop | `state.currentText = ...`, `state.currentNativeCalls = List.of()` after approval denial, successful mutation summaries, terminal read-only answer, engine/model error answer | Coherent enough for a separate helper that means "finish with this answer and no further tool calls". It must not imply success or failure by itself. |
| Retry/continuation setup | `state.currentText = ""`, `state.currentNativeCalls = List.of(repairCall)` and `state.currentText/currentNativeCalls = repromptResult...` | Not terminal. Do not hide this behind terminal helpers. |
| Compact continuation result | compact mutation/read-only continuations assigning text/tool calls and sometimes `FailureDecision.continueLoop()` | Mixed. Leave in current owner until compact-continuation ownership is inspected separately. |
| Loop fallback/finalization | unfinished tool continuation fallback, iteration-limit suffix, `finalizeAnswer(...)` | Belongs to `ToolCallLoop` orchestration for now. Do not move in the terminal-response slice. |
| Failure wording/trace | pending obligation, static repair, stale reread, context-budget wording, action-obligation trace | Must stay with the policy/guard owner that already knows the reason and trace semantics. |

## Decision

Do not extract a broad `ToolLoopTerminalResponse` service yet.

The correct implementation slice is smaller:

```text
[T536] Add LoopState terminal response helpers
```

T536 should add explicit methods on `LoopState` for the repeated terminal
state mutation:

```text
finishWithAnswer(String answer)
stopWithFailure(FailureDecision decision, String answer)
```

The methods should do only this:

- preserve the exact answer string provided by the caller;
- set `currentNativeCalls` to `List.of()`;
- in the failure method, set `failureDecision` to the provided stop decision;
- not sanitize, strip, summarize, trace, classify, or choose wording;
- not clear pending obligations unless the existing call site already does
  that separately.

T536 should migrate only terminal stop call sites that already set no further
native tool calls. It must not change retry/continuation setup, compact
continuation result application, model result application, `finalizeAnswer`,
or any final-answer wording.

This keeps ownership honest:

- policy owners still decide why the turn stops;
- wording owners still build exact answers;
- trace owners still record trace events;
- `LoopState` owns the low-level invariant for terminal response state.

## Rejected Alternatives

### Extract `ToolLoopTerminalResponse` now

Rejected for T536.

Reason: that value would tempt the next ticket to move reason selection,
answer wording, trace recording, and failure semantics into one object. The
source evidence does not support that yet.

### Move model/engine error answers first

Rejected for immediate implementation.

Reason: there is duplication between `ToolRepromptChatExecutor` and
`ToolRepromptOverlayContinuation`, but it is not the same ownership problem as
terminal state application. Error wording and retry handling need a separate
decision if selected later.

### Apply helpers to continuation setup

Rejected.

Reason: continuation setup is intentionally not terminal. Hiding repair calls,
compact mutation continuation, or normal reprompt results behind terminal
helpers would make the loop less readable.

### Change final answer sanitization/finalization

Rejected.

Reason: `ToolCallLoop.finalizeAnswer(...)` also handles suspicious HTML,
tool-call stripping, and protected-content sanitization. That is a separate
final-output ownership decision, not T536.

## Acceptance Criteria

- Inspect every current assignment to `state.currentText`,
  `state.currentNativeCalls`, and `state.failureDecision`.
- Classify terminal failure, terminal non-failure, retry/continuation,
  compact continuation, loop fallback, and wording/trace ownership.
- Select a narrow implementation ticket or explicitly reject implementation.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
