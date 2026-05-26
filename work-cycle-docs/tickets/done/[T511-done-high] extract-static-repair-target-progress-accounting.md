# [T511-done-high] Extract Static Repair Target Progress Accounting

## Status

Done.

## Scope

T511 extracts static full-rewrite repair target accounting from
`ToolCallRepromptStage` into `StaticRepairTargetProgressAccounting`.

The ticket preserves runtime behavior, prompt wording, chat execution,
transient retry behavior, engine-error wording, post-mutation continuation
ordering, expected-target progress, pending-obligation wording, tool-surface
selection, protected-path behavior, trace wording, and static-web diagnostics.

## What Changed

- Added `dev.talos.runtime.toolcall.StaticRepairTargetProgressAccounting`.
- Moved deterministic static repair target progress calculation out of
  `ToolCallRepromptStage`:
  - `hasStaticRepairContext(LoopState state)`;
  - `remainingFullRewriteRepairTargets(LoopState state)`.
- `ToolCallRepromptStage` now delegates static full-rewrite target progress to
  the new owner in both call sites.
- Removed the now-stale `RepairPolicy` and `Set` imports from
  `ToolCallRepromptStage`.
- Added focused tests for:
  - subtracting successful mutating outcomes from rendered full-write targets;
  - preserving existing path normalization semantics;
  - ignoring failed and read-only outcomes;
  - including runtime-owned `state.staticWebFullRewriteRequiredTargets`;
  - detecting rendered static repair context;
  - proving the stage no longer owns the private static repair target helpers.

## Verification Notes

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairTargetProgressAccountingTest" --no-daemon
```

failed at compile time because `StaticRepairTargetProgressAccounting` did not
exist.

The first GREEN run exposed an incorrect test expectation: current
`ToolCallSupport.normalizePath(...)` converts backslashes to slashes but does
not strip leading `./`. T511 is a behavior-preserving extraction, so the test
was corrected to verify backslash normalization without introducing new
leading-dot behavior.

## Commands

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairTargetProgressAccountingTest" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T511 `ToolCallRepromptStage` shape before choosing T512.
Do not assume chat execution or post-mutation continuation sequencing is safe
to extract without a fresh decision ticket.
