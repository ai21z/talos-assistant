# [T540-done-high] Tool Loop Final Answer Finalization Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T540`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `062b6cca`
Predecessor: `T539`

## Scope

T540 inspects the final-answer finalization boundary selected by T539 before
moving any code.

This ticket intentionally makes no runtime code change.

## Source Evidence

Measured from fresh `origin/v0.9.0-beta-dev` at `062b6cca`.

Primary inspection commands:

```powershell
rg -n "finalizeAnswer|unresolvedContinuationFallback|shouldSuppressUnfinishedToolContinuation|Tool-call continuation could not be completed|Tool-call limit reached|stripSuspiciousHtml|contentWithheldFromModelContext|ProtectedContentPolicy\.sanitizeText|ToolCallParser\.stripToolCalls" src/main/java/dev/talos/runtime src/test/java/dev/talos/runtime work-cycle-docs/tickets/done
rg -n "ToolCallLoopFinal|FinalAnswer|finalizer|ToolLoopFinal|final answer finalization|finalization" src/main/java/dev/talos src/test/java/dev/talos work-cycle-docs/tickets/done
```

Current source shape:

| Source | Evidence |
|---|---|
| `ToolCallLoop.java` | Imports `Sanitize` and `ProtectedContentPolicy` only for final-answer shaping. |
| `ToolCallLoop.java` | Suppresses unfinished tool-call continuation before breaking the loop by replacing current text with `[Tool-call continuation could not be completed. No further tool calls were executed.]`. |
| `ToolCallLoop.java` | Applies iteration-limit suffix by stripping tool calls and appending `[Tool-call limit reached. Some tool calls were not executed.]`. |
| `ToolCallLoop.java` | Finalizes the `LoopResult` answer through `finalizeAnswer(currentText, totalToolsInvoked, contentWithheldFromModelContext)`. |
| `ToolCallLoop.finalizeAnswer(...)` | Rechecks unfinished tool-call payload suppression, strips tool-call blocks, strips suspicious HTML, then redacts protected content if model context was withheld. |
| `ToolCallParser.stripToolCalls(...)` | Public and already owns protocol/tool-call text removal. |
| `ToolCallParser.looksLikeUnfinishedToolPayload(...)` | Package-private, so an extracted owner that uses it should live in `dev.talos.runtime`, not `dev.talos.runtime.toolcall`, unless access is deliberately changed. |
| `Sanitize.stripSuspiciousHtml(...)` | Pure sanitizer primitive. |
| `ProtectedContentPolicy.sanitizeText(...)` | Runtime privacy redaction facade over safety sanitization. |

Measured line counts:

| File | Lines |
|---|---:|
| `ToolCallLoop.java` | 531 |
| `ToolCallParser.java` | 432 |
| `Sanitize.java` | 279 |
| `ProtectedContentPolicy.java` | 85 |

Existing coverage around this boundary:

| Test | Existing coverage |
|---|---|
| `ToolCallLoopTest.noToolCallsReturnsOriginalAnswer` | Normal answer passes through. |
| `ToolCallLoopTest.nullAnswerReturnsEmpty` | Null initial answer becomes an empty final answer. |
| `ToolCallLoopTest` malformed continuation case | Raw unfinished tool payload does not leak; final answer contains the unresolved-continuation fallback. |
| `ToolCallLoopTest.loopResultStripsToolCallsFromFinalAnswer` | Final answer strips `<tool_call>` blocks. |
| `NativeToolPipelineTest.sanitizeStripsHtmlOutsideToolCalls` | Sanitizer strips suspicious script tags in prose. |
| `ToolResultModelContextHandoffTest` | Handoff can set `contentWithheldFromModelContext`, but final-answer redaction is not directly owned by a focused finalizer test today. |

## Decision

The next implementation ticket should be:

```text
[T541] Extract tool loop final answer finalizer
```

Recommended owner:

```text
src/main/java/dev/talos/runtime/ToolLoopFinalAnswerFinalizer.java
```

Keep it in package `dev.talos.runtime` because it must use the current
package-private unfinished-tool payload predicate without widening parser API
surface just for this extraction.

T541 should move the final-output mechanics out of `ToolCallLoop`:

- unresolved continuation fallback text;
- unfinished tool-call payload suppression predicate;
- iteration-limit final-answer suffix application;
- final answer tool-call stripping;
- final answer suspicious HTML stripping;
- protected-content redaction when model context was withheld.

`ToolCallLoop` should remain the orchestrator:

- execute parse/execute/reprompt iterations;
- decide whether the loop hit the iteration limit;
- log iteration-limit events;
- assemble `LoopResult` fields.

## T541 Implementation Shape

Add:

```text
dev.talos.runtime.ToolLoopFinalAnswerFinalizer
```

Expected package-private methods:

```text
static String withIterationLimitNotice(String currentText)
static String finalizeAnswer(String currentText, int toolsInvoked, boolean contentWithheldFromModelContext)
```

The implementation may keep helper methods private:

```text
shouldSuppressUnfinishedToolContinuation(...)
unresolvedContinuationFallback()
```

`ToolCallLoop` should call:

```text
state.currentText = ToolLoopFinalAnswerFinalizer.withIterationLimitNotice(state.currentText)
String finalAnswer = ToolLoopFinalAnswerFinalizer.finalizeAnswer(...)
```

This keeps detection and loop progression in `ToolCallLoop`, while giving final
answer shaping one owner.

## T541 Test Shape

Add focused tests for `ToolLoopFinalAnswerFinalizer`.

Required assertions:

- normal text passes through unchanged;
- null text finalizes to empty text;
- finalization strips text-path tool-call blocks;
- finalization strips suspicious HTML from prose;
- unfinished tool-call payload after one or more invoked tools returns the
  exact unresolved-continuation fallback;
- unfinished-looking payload with zero invoked tools does not trigger that
  fallback unless current behavior already does so;
- iteration-limit notice strips tool-call blocks and appends the exact current
  limit warning;
- protected/private canary text is redacted when
  `contentWithheldFromModelContext` is `true`;
- the same text is not redacted by this finalizer path when
  `contentWithheldFromModelContext` is `false`, unless another sanitizer rule
  independently strips it.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolLoopFinalAnswerFinalizerTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

Final gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Explicit Non-Moves For T541

T541 must not change:

- final-answer wording;
- unresolved continuation fallback wording;
- iteration-limit suffix wording;
- parser behavior;
- sanitizer behavior;
- protected-content policy semantics;
- `LoopResult` field population;
- reprompt ordering;
- compact mutation continuation;
- compact read-only evidence continuation;
- normal reprompt result application;
- trace wording.

## Rejected Alternatives

### Leave finalization in `ToolCallLoop`

Rejected.

Reason: final-answer shaping is now the remaining central output-safety
mechanism in the current hygiene lane. It pulls protocol stripping, suspicious
HTML stripping, unfinished-tool suppression, and protected-content redaction
into the loop orchestrator. That is no longer the best ownership boundary.

### Extract only protected-content redaction

Rejected.

Reason: redaction is ordered after tool-call stripping and suspicious HTML
stripping. Moving only that call would leave the actual final-output policy
spread across two places and make audit reasoning worse.

### Put the finalizer under `dev.talos.runtime.toolcall`

Rejected for T541.

Reason: the finalizer should not force `ToolCallParser.looksLikeUnfinishedToolPayload(...)`
to become public. Keeping the owner in `dev.talos.runtime` preserves access
without widening the parser API.

### Move `LoopResult` construction

Rejected.

Reason: `LoopResult` assembly includes counters, path read sets, cushion
metrics, failure decisions, and tool outcomes. That remains loop orchestration,
not final-answer shaping.

## Acceptance Criteria

- Inspect final-answer finalization from fresh beta.
- Distinguish final-answer shaping from loop orchestration.
- Select one coherent implementation owner.
- Define focused regression tests before code movement.
- Make no code changes.
- Commit only this ticket document.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
