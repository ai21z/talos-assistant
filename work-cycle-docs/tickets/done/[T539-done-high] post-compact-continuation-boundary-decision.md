# [T539-done-high] Post Compact Continuation Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T539`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `32a0c855`
Predecessor: `T538`

## Scope

T539 reinspects the post-T538 tool-loop response-state and continuation
ownership from fresh beta before selecting another implementation ticket.

This ticket intentionally makes no code changes.

## Source Evidence

Measured from fresh `origin/v0.9.0-beta-dev` at `32a0c855`.

Primary inspection command:

```powershell
rg -n "state\.currentText\s*=|state\.currentNativeCalls\s*=|state\.failureDecision\s*=|finishWithAnswer|stopWithFailure|CompactReadOnlyEvidenceContinuation|CompactMutationContinuationExecutor|finalizeAnswer|ToolRepromptChatExecutor" src/main/java/dev/talos/runtime src/test/java/dev/talos/runtime
```

Current source shape:

| Area | Source | Current owner assessment |
|---|---|---|
| Context-budget fallback ordering | `ToolRepromptContextBudgetHandler.java` | Correctly a router after T538. It records the context-budget skip, gives pending obligations first refusal, delegates compact mutation continuation, tries compact read-only evidence, then applies deterministic context-budget stop. |
| Compact mutation continuation execution | `CompactMutationContinuationExecutor.java` | Correctly extracted by T538. It owns plan lookup, compact LLM execution, loop-state result application, trace/action-obligation records, no-tool stop decision, and outcome classification. |
| Compact read-only evidence continuation | `CompactReadOnlyEvidenceContinuation.java` | Already isolated. It owns evidence eligibility, compact answer messages, tool-call rejection, state application, pending-obligation clearing, and read-only compact trace warnings. |
| Normal reprompt result application | `ToolRepromptChatExecutor.java` | Keep local. Applying raw `LlmClient.StreamResult` text/native calls is the chat-executor's direct responsibility, not terminal response finalization. |
| Repair-call setup | `ToolRepromptPathPolicyBlockedDecision.java` | Keep local. It intentionally prepares a repair native tool call and continues the loop. |
| Non-terminal failure signal | `ToolFailureIterationSignals.java` | Keep local. It updates failure-policy state and does not choose final answer text. |
| Loop fallback and final answer finalization | `ToolCallLoop.java` | Still mixed. It handles unfinished tool-call continuation suppression, iteration-limit suffixing, tool-call stripping, suspicious HTML stripping, and protected-content sanitization. |

Measured line counts:

| File | Lines |
|---|---:|
| `ToolRepromptContextBudgetHandler.java` | 82 |
| `CompactMutationContinuationExecutor.java` | 86 |
| `CompactReadOnlyEvidenceContinuation.java` | 188 |
| `ToolRepromptChatExecutor.java` | 148 |
| `ToolCallLoop.java` | 531 |

## Decision

Do not extract another compact-continuation class now.

Do not move normal reprompt result application out of
`ToolRepromptChatExecutor`.

Do not mechanically hide every remaining `state.currentText` or
`state.currentNativeCalls` write behind `LoopState` helpers.

The next ticket should be a decision/inspection ticket for final answer
finalization:

```text
[T540] Tool Loop Final Answer Finalization Decision
```

T540 should inspect whether `ToolCallLoop.finalizeAnswer(...)` and adjacent
fallback handling form one coherent owner, likely a later implementation such
as `ToolLoopFinalAnswerFinalizer`.

The candidate owner must be decided carefully because finalization crosses:

- unfinished tool-call payload suppression;
- iteration-limit answer suffixing;
- text-path tool-call stripping;
- suspicious HTML stripping;
- protected-content sanitization when content was withheld from model context;
- `LoopResult` final-answer truthfulness.

## Explicit Non-Moves For T540 Planning

T540 must not start by moving code before source inspection.

It must not change:

- final-answer wording;
- unresolved continuation fallback wording;
- iteration-limit suffix wording;
- `ToolCallParser.stripToolCalls(...)` behavior;
- `Sanitize.stripSuspiciousHtml(...)` behavior;
- protected-content redaction behavior;
- `LoopResult` field population;
- compact mutation continuation;
- compact read-only evidence continuation;
- normal reprompt result application.

## Rejected Alternatives

### Move compact read-only evidence continuation next

Rejected.

Reason: `CompactReadOnlyEvidenceContinuation` is already the owner extracted in
T448. It currently combines eligibility, answer synthesis, rejection, trace,
pending-obligation clearing, and terminal state application for that one
fallback. Further movement now would be internal cleanup, not ownership repair.

### Move normal reprompt result application next

Rejected.

Reason: `ToolRepromptChatExecutor` is already the correct owner for applying
raw model stream results into loop state. Extracting that assignment into a
generic helper would blur active continuation state with terminal answer state.

### Extract only suspicious HTML stripping

Rejected.

Reason: final answer sanitation is not only HTML stripping. It is ordered after
tool-call stripping and before protected-content redaction. Splitting one line
would make final-output policy harder to audit.

### Leave finalization unexamined and jump to another unrelated lane

Rejected.

Reason: the current hygiene lane is still about tool-loop response and outcome
truthfulness. `ToolCallLoop.finalizeAnswer(...)` is the remaining central
final-output boundary in this lane.

## Acceptance Criteria

- Inspect post-T538 continuation and response-state ownership from fresh beta.
- Confirm `ToolRepromptContextBudgetHandler` is now only the fallback router.
- Confirm compact mutation continuation execution has an owner after T538.
- Confirm compact read-only continuation and normal reprompt result application
  should not be moved next.
- Select the next ticket as a decision ticket, not an implementation ticket.
- Make no code changes.
- Commit only this ticket document.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
