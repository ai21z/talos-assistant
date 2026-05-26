# [T529-done-high] Extract Static Selector Repair Write Guard

Status: done
Priority: high
Date: 2026-05-27
Branch: `T529`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `4009a9b9`
Predecessor: `T528`

## Scope

T529 implements the T528 decision: extract only static selector repair write
failure handling out of `LoopState`.

The ticket intentionally does not move generic pending-obligation breach
enforcement, static selector parsing/matching, `PendingActionObligation`
failure text, `ToolCallLoop` safety-gate ordering, approval policy, or tool
execution.

## Changes

- Added `dev.talos.runtime.toolcall.StaticSelectorRepairWriteGuard`.
- Moved selector repair failure reason and final-answer construction into the
  guard.
- Kept selector violation detection in the existing
  `dev.talos.runtime.repair.StaticSelectorRepairGuard`.
- Updated `LoopState.failStaticSelectorRepairAfterInvalidWriteContent(...)` to
  delegate evaluation to the new guard while still applying loop state and
  recording the existing trace event.
- Added focused guard ownership and behavior tests.

## Preserved Behavior

- `ToolCallLoop` still checks pending-obligation breach first, then static
  repair invalid-write content, then static selector invalid-write content.
- Static selector repair writes that preserve verifier-known missing selectors
  are still stopped before approval and before tool execution.
- The trace event still uses:
  - event type: `ACTION_OBLIGATION_EVALUATED`;
  - obligation: `STATIC_SELECTOR_REPAIR`;
  - status: `FAILED`;
  - failure kind: `STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR`.
- Existing failure reason wording is preserved.
- Existing final answer wording is preserved.
- Valid selector repair replacements that remove the verifier-known missing
  selector still pass this guard.
- No behavior changes are intended for generic pending-obligation breach
  enforcement.

## TDD Evidence

- RED: `StaticSelectorRepairWriteGuardTest` failed at compile time before
  implementation because `StaticSelectorRepairWriteGuard` did not exist.
- GREEN: the focused guard test passed after adding the guard and delegating
  selector repair write evaluation from `LoopState`.
- Focused loop-level selector repair tests passed after the extraction.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticSelectorRepairWriteGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticSelectorRepairWriteGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingCssSelectorBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairRejectsPreservedMissingJavaScriptSelectorBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.staticSelectorRepairAllowsReplacementThatRemovesKnownMissingSelector" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- RED focused test: failed at `compileTestJava` before implementation because
  `StaticSelectorRepairWriteGuard` did not exist.
- GREEN focused guard test: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 4
  executed, 2 up-to-date).
- Focused selector repair loop tests: passed (`BUILD SUCCESSFUL`; 6 actionable
  tasks: 1 executed, 5 up-to-date).
- `git diff --check`: passed, line-ending warning only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 8 executed, 6 up-to-date).

## Next Step

After T529 is integrated, inspect the post-extraction `LoopState` shape before
choosing T530. Generic pending-obligation breach extraction is still
safety-sensitive and should not be started without a fresh source inspection.
