# [T528-done-high] Post Static Repair Write Guard Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T528`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `2582b3d3`
Predecessor: `T527`

## Scope

T528 is a no-code inspection and decision ticket for the post-T527
`LoopState` obligation/guard boundary.

T527 extracted full-rewrite static repair write-content validation into
`StaticRepairWriteContentGuard`. This ticket checks whether the next correct
move is generic pending-obligation breach extraction, another focused
pre-approval repair guard, or a pause.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `2582b3d3`:

| File | Lines | Current role |
|---|---:|---|
| `LoopState.java` | 451 | Mutable loop state, pending-obligation lifecycle, generic breach enforcement, static repair guard application, static selector repair guard application, loop counters/evidence state. |
| `PendingActionObligation.java` | 121 | Obligation value, target normalization, failure wording, and raised/breached trace recording. |
| `StaticRepairWriteContentGuard.java` | 103 | Full-rewrite static repair write-content classification and failure wording. |
| `StaticSelectorRepairGuard.java` | 165 | Static selector repair violation detection from static repair context and replacement content. |
| `ToolCallLoop.java` | 531 | Parse/execute/reprompt loop orchestration and pre-execution safety checkpoints. |

## Source Evidence

After T527, `ToolCallLoop` still calls these pre-execution gates in order:

```java
state.failPendingActionObligationAfterInvalidToolCalls(parsed.calls())
state.failStaticRepairAfterInvalidWriteContent(parsed.calls())
state.failStaticSelectorRepairAfterInvalidWriteContent(parsed.calls())
```

`LoopState.failStaticRepairAfterInvalidWriteContent(...)` is now an applicator:

- asks `StaticRepairWriteContentGuard.evaluate(messages, calls)`;
- applies `FailureDecision.stop(...)`;
- sets the final answer;
- clears native calls;
- records `STATIC_REPAIR_WRITE_CONTENT` /
  `STATIC_REPAIR_INVALID_WRITE_CONTENT`.

`LoopState.failStaticSelectorRepairAfterInvalidWriteContent(...)` still mixes
two concerns:

- classification through `StaticSelectorRepairGuard.violationForWrite(...)`;
- failure reason/final answer construction;
- failure decision mutation;
- native-call clearing;
- trace emission for `STATIC_SELECTOR_REPAIR` /
  `STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR`.

Generic pending-obligation breach enforcement still spans multiple obligation
kinds:

- `EXPECTED_TARGETS_REMAINING`;
- `OLD_STRING_MISS_TARGET_REPAIR`;
- `APPEND_LINE_TARGET_REPAIR`;
- `EXPECTED_TARGET_SCOPE_REPAIR`;
- `STATIC_REPAIR_TARGETS_REMAINING`.

That branch still contains target matching, static-web defer behavior,
kind-specific detail wording, state mutation, native-call clearing, and
breached trace recording.

## Decision

Do not extract generic pending-obligation breach enforcement next.

The next implementation ticket should extract only the static selector repair
write guard:

```text
[T529] Extract static selector repair write guard
```

Recommended owner:

```text
dev.talos.runtime.toolcall.StaticSelectorRepairWriteGuard
```

Recommended API shape:

```java
record Failure(String reason, String answer) {}

static Optional<Failure> evaluate(List<ChatMessage> messages, List<ToolCall> calls)
```

The guard should own:

- iterating candidate tool calls;
- delegating selector violation detection to
  `StaticSelectorRepairGuard.violationForWrite(...)`;
- constructing the exact existing reason:
  `STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR: ...`;
- constructing the exact existing final answer text;
- exposing constants for:
  - obligation: `STATIC_SELECTOR_REPAIR`;
  - failure kind: `STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR`.

`LoopState.failStaticSelectorRepairAfterInvalidWriteContent(...)` should keep
only state application:

- call `StaticSelectorRepairWriteGuard.evaluate(messages, calls)`;
- return false if no failure exists;
- set `FailureDecision.stop(FailureAction.ASK_USER, failure.reason())`;
- set `currentText` to `failure.answer()`;
- clear `currentNativeCalls`;
- record the existing trace payload using the guard constants.

This mirrors the T527 shape and removes selector-repair failure wording from
`LoopState` without touching the generic pending-obligation state machine.

## Rejected Alternatives

### Extract generic pending-obligation breach enforcement now

Rejected for T529.

Reason: it still crosses expected-target mutation enforcement, static-web
policy defer behavior, three compact-repair obligation kinds, static-repair
pending obligations, trace breach recording, and state mutation. That is not a
single safe implementation step.

### Move `StaticSelectorRepairGuard` itself

Rejected.

Reason: `StaticSelectorRepairGuard` already owns selector-fact parsing and
violation detection. T529 should not change that parser or its package
ownership. The missing owner is the loop-facing write-guard adapter that turns
a violation into the existing failure reason and answer.

### Move trace recording out of `LoopState`

Rejected for T529.

Reason: T529 should preserve the T527 pattern. Guard classes classify and build
failure text; `LoopState` applies mutable loop state and records trace events.

### Change `ToolCallLoop` gate ordering

Rejected.

Reason: the ordering is safety behavior and must remain unchanged.

## Explicit Non-Goals For T529

Do not combine static selector repair write guard extraction with:

- generic pending-obligation breach enforcement;
- `PendingActionObligation` failure text or trace methods;
- `StaticRepairWriteContentGuard`;
- `StaticSelectorRepairGuard` parsing or matching behavior;
- `ToolCallLoop` gate ordering;
- approval policy;
- tool execution;
- final-answer wording changes.

## Expected T529 Verification Shape

T529 should use a RED/GREEN ownership test before implementation:

- `LoopState` delegates selector repair write evaluation to
  `StaticSelectorRepairWriteGuard.evaluate(messages, calls)`;
- `LoopState` no longer imports `StaticSelectorRepairGuard`;
- `LoopState` no longer contains
  `staticSelectorRepairFailureAnswer(...)`;
- `StaticSelectorRepairWriteGuard` owns the exact failure reason and final
  answer text.

Focused behavior tests should include:

- `ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingCssSelectorBeforeApply`;
- `ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingJavaScriptSelectorBeforeApply`;
- `ToolCallLoopTest.staticSelectorRepairAllowsReplacementThatRemovesKnownMissingSelector`;
- a new focused `StaticSelectorRepairWriteGuardTest`.

Required verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticSelectorRepairWriteGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingCssSelectorBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingJavaScriptSelectorBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairAllowsReplacementThatRemovesKnownMissingSelector" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Acceptance Criteria

- The post-T527 `LoopState` boundary is inspected from fresh beta.
- No code changes are made.
- Generic pending-obligation breach extraction is rejected for the next ticket.
- Static selector repair write guard extraction is selected as the next
  coherent implementation.
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
