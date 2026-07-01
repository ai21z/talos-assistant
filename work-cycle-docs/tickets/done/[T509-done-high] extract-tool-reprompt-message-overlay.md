# [T509-done-high] Extract Tool Reprompt Message Overlay

## Status

Done.

Replacement PR note: PR #176 supersedes PR #175 because GitHub did not create
current-head CI for #175 after the review fix, even after branch updates and
reopen attempts.

## Scope

T509 extracts the temporary reprompt message overlay from
`ToolCallRepromptStage` into `ToolRepromptMessageOverlay`.

The ticket preserves runtime behavior, prompt wording, failure handling,
transient retry behavior, tool-surface selection, pending-obligation selection,
post-mutation continuation decisions, and static repair target calculation.

## What Changed

- Added `dev.talos.runtime.toolcall.ToolRepromptMessageOverlay`.
- Moved temporary message insertion and cleanup into the overlay owner:
  - stale edit repair prompt;
  - empty edit repair prompt;
  - static repair progress prompt;
  - expected target progress prompt;
  - bounded current-task anchor prompt.
- Kept prompted-path side effects with the overlay owner.
- Kept cleanup guarded by the existing system-message content prefixes.
- Kept `ToolCallRepromptStage` as the orchestration facade for:
  - remaining target calculation;
  - pending action obligation decisions;
  - reprompt tool-surface selection;
  - chat reprompt execution;
  - engine error handling and exact fallback wording.
- Snapshotted request messages after applying the overlay so the manual
  transient retry keeps the same temporary guidance after overlay cleanup.

## Verification Notes

The RED ownership test failed before implementation because
`ToolRepromptMessageOverlay` did not exist.

PR review then identified that the stage's manual transient retry could lose
temporary overlay messages if the request message list still aliased
`state.messages` after overlay cleanup. The regression test was tightened to
exhaust `LlmClient`'s internal transient retry budget first, then prove the
stage-level retry still receives `[Expected target progress]` and the current
task anchor. The stage now snapshots request messages after applying the
overlay.

The focused tests cover:

- stale and empty repair message insertion and prompted-path side effects;
- exact static-repair and expected-target progress wording;
- 500-character current-task anchor truncation;
- cleanup after normal close;
- cleanup after an exception in the continuation path;
- transient retry preserving the temporary overlay payload;
- `ToolCallRepromptStage` no longer owning inline temporary-message indexes or
  cleanup prefix checks.

## Commands

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptMessageOverlayTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --tests "dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptMessageOverlayTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --tests "dev.talos.core.llm.ToolCallRepromptStagePromptDebugTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T509 `ToolCallRepromptStage` shape before choosing T510.
Do not assume the next slice is another extraction. Likely candidates are
post-mutation continuation selection, chat reprompt execution, or static
full-rewrite repair-target calculation, but the next owner should be selected
from source evidence.
