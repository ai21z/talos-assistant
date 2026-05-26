# [T527-done-high] Extract Static Repair Write Content Guard

Status: done
Priority: high
Date: 2026-05-27
Branch: `T527`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `782b0cf7`
Predecessor: `T526`

## Scope

T527 implements the T526 decision: extract only full-rewrite static repair
write-content validation out of `LoopState`.

The ticket intentionally does not move generic pending-obligation breach
enforcement, static selector repair handling, `PendingActionObligation`
failure text, `ToolCallLoop` safety-gate ordering, approval policy, or tool
execution.

## Changes

- Added `dev.talos.runtime.toolcall.StaticRepairWriteContentGuard`.
- Moved static full-rewrite repair write-content classification into the
  guard:
  - full-rewrite target lookup from repair context;
  - target write matching;
  - accepted content parameter lookup;
  - missing content rejection;
  - blank content rejection;
  - template-placeholder content rejection.
- Moved the static repair invalid-write failure answer construction into the
  guard.
- Updated `LoopState.failStaticRepairAfterInvalidWriteContent(...)` to delegate
  evaluation to the guard while still applying loop state and recording the
  existing trace event.
- Updated pending static-repair breach enforcement to reuse the guard's
  `invalidWriteDetail(...)` helper without moving the broader breach state
  machine.
- Added focused guard ownership and behavior tests.

## Preserved Behavior

- `ToolCallLoop` still checks pending-obligation breach first, then static
  repair invalid-write content, then static selector invalid-write content.
- Invalid static repair writes are still stopped before approval and before
  any tool execution.
- The trace event still uses:
  - event type: `ACTION_OBLIGATION_EVALUATED`;
  - obligation: `STATIC_REPAIR_WRITE_CONTENT`;
  - status: `FAILED`;
  - failure kind: `STATIC_REPAIR_INVALID_WRITE_CONTENT`.
- Existing final answer wording for static repair invalid-write stops is
  preserved.
- Existing failure reason wording for missing, blank, and placeholder content
  is preserved.
- Non-target writes remain outside this guard.
- No behavior changes are intended for static selector repair handling.
- No behavior changes are intended for generic pending-obligation breach
  enforcement.

## TDD Evidence

- RED: `StaticRepairWriteContentGuardTest` failed at compile time before
  implementation because `StaticRepairWriteContentGuard` did not exist.
- GREEN: the focused guard test passed after adding the guard and delegating
  static repair write-content evaluation from `LoopState`.
- Focused loop-level tests for pre-approval static repair stops passed after
  the extraction.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairWriteContentGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairWriteContentGuardTest" --tests "dev.talos.runtime.TemplatePlaceholderGuardTest" --tests "dev.talos.runtime.ToolCallLoopTest.firstStaticRepairRejectsEmptyWriteBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.pendingStaticRepairRejectsEmptyWriteBeforeApply" --tests "dev.talos.runtime.ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.narrowedStaticRepairProgressBreachReportsOnlyVerifierSpecificTarget" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- RED focused test: failed at `compileTestJava` before implementation because
  `StaticRepairWriteContentGuard` did not exist.
- GREEN focused guard test: passed (`BUILD SUCCESSFUL`; 6 actionable tasks: 4
  executed, 2 up-to-date).
- Focused static repair/template-placeholder loop tests: passed
  (`BUILD SUCCESSFUL`; 6 actionable tasks: 1 executed, 5 up-to-date).
- `git diff --check`: passed, line-ending warning only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 8 executed, 6 up-to-date).

## Next Step

After T527 is integrated, inspect the post-extraction `LoopState` shape before
choosing T528. Do not move generic pending-obligation breach enforcement unless
the next inspection proves a coherent smaller owner and exact behavior tests.
