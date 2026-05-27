# [T542-done-high] Close Tool Loop Response Finalization Lane

Status: done
Priority: high
Date: 2026-05-27
Branch: `T542`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `a6cd8953`
Predecessor: `T541`

## Scope

T542 reinspects the post-T541 tool-loop response and final-output shape before
starting more implementation work.

This ticket intentionally makes no code changes.

## Source Evidence

Measured from fresh `origin/v0.9.0-beta-dev` at `a6cd8953`.

Primary inspection commands:

```powershell
rg -n "state\.currentText\s*=|state\.currentNativeCalls\s*=|state\.failureDecision\s*=" src/main/java/dev/talos/runtime
rg -n "ToolLoopFinalAnswerFinalizer|finishWithAnswer|stopWithFailure|currentText\s*=|currentNativeCalls\s*=|failureDecision\s*=|LoopResult" src/main/java/dev/talos/runtime/ToolCallLoop.java src/main/java/dev/talos/runtime/toolcall
rg -n "record ToolOutcome|record LoopResult|record MutationEvidence|static String buildCallSignature|static ToolCall repairMissingPath" src/main/java/dev/talos/runtime/ToolCallLoop.java
```

Current source shape:

| Area | Source | Current owner assessment |
|---|---|---|
| Terminal answer state | `LoopState.finishWithAnswer(...)`, `LoopState.stopWithFailure(...)` | Acceptable. Terminal answer/native-call clearing has a single low-level owner. |
| Final answer shaping | `ToolLoopFinalAnswerFinalizer.java` | Acceptable after T541. It owns unresolved continuation fallback, iteration-limit answer notice, tool-call stripping, suspicious HTML stripping, and withheld-content redaction. |
| Compact mutation continuation result application | `CompactMutationContinuationExecutor.java` | Acceptable. It owns the compact mutation LLM result and continuation/stop classification. |
| Compact read-only evidence answer | `CompactReadOnlyEvidenceContinuation.java` | Acceptable. It owns eligibility, compact answer synthesis, tool-call rejection, trace warnings, and terminal state application for that fallback. |
| Normal reprompt result application | `ToolRepromptChatExecutor.java` | Acceptable. It owns raw `LlmClient.StreamResult` application to loop state and empty-result fallback. |
| Repair-call setup | `ToolRepromptPathPolicyBlockedDecision.java` | Acceptable. It intentionally prepares a repair native call and continues the loop. |
| Non-terminal failure signal | `ToolFailureIterationSignals.java` | Acceptable. It records failure-policy state, not final answer text. |
| Main loop orchestration | `ToolCallLoop.java` | Acceptable for now. It parses, executes, reprompts, applies finalizer output, and assembles `LoopResult`. |

Measured line counts:

| File | Lines |
|---|---:|
| `ToolCallLoop.java` | 512 |
| `ToolLoopFinalAnswerFinalizer.java` | 35 |
| `ToolCallRepromptStage.java` | 115 |
| `LoopState.java` | 181 |
| `ToolRepromptChatExecutor.java` | 148 |

Remaining direct production response-state assignments:

| Source | Decision |
|---|---|
| `ToolCallLoop.java` unresolved-continuation fallback | Keep. The finalizer owns the text; the loop owns the break point where the fallback is applied. |
| `ToolCallLoop.java` iteration-limit notice | Keep. The finalizer owns final-output shaping; the loop owns iteration-limit detection and logging. |
| `CompactMutationContinuationExecutor.java` result application | Keep. This is active continuation state, not terminal response state. |
| `CompactReadOnlyEvidenceContinuation.java` result application | Keep. This is already its own compact evidence fallback owner. |
| `ToolRepromptChatExecutor.java` result application | Keep. This is raw model result continuation state. |
| `ToolRepromptPathPolicyBlockedDecision.java` repair setup | Keep. This is an intentional repair tool-call continuation. |
| `ToolFailureIterationSignals.java` failure signal | Keep. This is non-terminal failure accounting. |

## Decision

Close the current tool-loop response/final-output lane.

Do not continue extracting from `ToolCallLoop` just because it still contains
branches or nested records.

The post-T541 response/final-output ownership is now good enough for beta
hygiene:

- terminal response state has `LoopState` helpers;
- compact mutation continuation has an executor;
- compact read-only evidence continuation has its own owner;
- normal chat reprompt application remains in the chat executor;
- final answer shaping has `ToolLoopFinalAnswerFinalizer`;
- `ToolCallLoop` is mostly loop orchestration plus compatibility/value surface.

The next ticket should be a decision/inspection ticket, not implementation:

```text
[T543] Tool Loop Outcome Value Boundary Decision
```

T543 should inspect whether the remaining nested outcome value surface should
stay nested in `ToolCallLoop` for compatibility or move toward dedicated
runtime outcome value types.

Target inspection set:

- `ToolCallLoop.LoopResult`;
- `ToolCallLoop.ToolOutcome`;
- `ToolCallLoop.MutationEvidence`;
- `ToolCallLoop.MutationSummary`;
- `ToolCallLoop.FileChange`;
- `ToolOutcomeFactory`;
- `ToolMutationEvidenceFactory`;
- `runtime.outcome.*` consumers;
- `runtime.verification.*` consumers;
- compatibility static wrappers in `ToolCallLoop`.

## Why T543 Must Be Planning First

`LoopResult`, `ToolOutcome`, and `MutationEvidence` are widely consumed by
runtime outcome renderers, static verifiers, trace recorders, tool-call tests,
and compatibility helpers. Moving them casually would create a broad API churn
ticket with high blast radius.

The correct question is not "can we move another class?" The correct question
is which outcome values are public compatibility surface, which are runtime
domain values, and which factory/helper wrappers are historical adapters.

## Rejected Next Moves

### Extract another method from `ToolCallLoop.run(...)`

Rejected.

Reason: the remaining `run(...)` method is mostly orchestration: parse,
pre-execution safety gates, execute, reprompt, apply finalizer, assemble
result. Extracting a random block would reduce locality without clarifying an
owner.

### Move `LoopResult` immediately

Rejected.

Reason: many packages and tests reference `ToolCallLoop.LoopResult` directly.
That may be the right future direction, but it needs a compatibility and
ownership decision first.

### Move `ToolOutcome` immediately

Rejected.

Reason: `ToolOutcome` is consumed by outcome rendering, protected-read guards,
static verification, mutation evidence, reprompt planning, trace recording, and
tests. A mechanical move would be noisy and risky.

### Hide the remaining `state.currentText` writes behind helpers

Rejected.

Reason: the remaining writes are not one semantic operation. They are active
continuation state, repair setup, non-terminal failure state, or loop fallback
application. The current owners are clearer than a generic helper would be.

## Acceptance Criteria

- Inspect post-T541 response/final-output ownership from fresh beta.
- Confirm T541 closed the final-answer finalization problem.
- Classify the remaining direct state writes.
- Close the current lane instead of starting another extraction.
- Select the next ticket as an outcome-value decision ticket.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
