# [T563] Extract permission decision trace event factory

## Summary

T563 extracts `PERMISSION_DECISION` trace event construction from
`LocalTurnTraceCapture` into a dedicated package-local owner:
`PermissionTraceEventFactory`.

`LocalTurnTraceCapture` remains the public thread-local facade. It still exposes
`recordPermissionDecision(...)`, but that method now delegates event
construction.

No permission policy, permission decision semantics, approval behavior, command
policy traces, checkpoint capture, protected-read postconditions,
action-obligation accounting, trace lifecycle, trace persistence, prompt-debug
lifecycle, private-document handoff policy, or artifact canary behavior changed.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = dc1abf28
talosVersion = 0.9.9
```

Predecessor:

```text
T562 = Local trace evidence shape decision
```

## Scope

Moved out of `LocalTurnTraceCapture`:

- `PERMISSION_DECISION` event construction;
- permission trace payload fields:
  `action`, `reasonCode`, `rememberEligible`, `protectedPath`, and optional
  redacted `pathHint`;
- permission trace `TraceRedactor.pathHint(relativePath)` call.

Kept in existing owners:

- `TurnProcessor` still owns permission-decision orchestration and approval
  flow.
- `PermissionPolicy` / `PermissionDecision` still own permission facts.
- `LocalTurnTraceCapture` still owns trace lifecycle, thread-local capture, and
  public facade entry points.
- `CommandTraceEventFactory` still owns command policy traces.
- `recordCheckpoint(...)` still owns checkpoint trace summary state and
  checkpoint event recording.

## Behavior preserved

The extracted factory preserves:

- exact event name: `PERMISSION_DECISION`;
- timestamp generation behavior;
- phase and tool name handling;
- exact payload keys and values;
- absent `pathHint` when the relative path is blank;
- protected path-hint redaction through `TraceRedactor.pathHint(...)`;
- raw tool payload exclusion from permission decision trace events.

## Tests

Added `LocalTurnTracePermissionDecisionTest`:

- verifies permission decision trace payload shape;
- verifies protected path redaction for `.env`;
- verifies raw tool payload text is not serialized into the trace;
- verifies `LocalTurnTraceCapture` delegates permission event construction to
  `PermissionTraceEventFactory`;
- verifies the factory owns the `PERMISSION_DECISION` event name, permission
  payload construction, and permission path-hint redaction.

## RED/GREEN evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePermissionDecisionTest" --no-daemon
```

The ownership test failed because `PermissionTraceEventFactory.java` did not
exist and `LocalTurnTraceCapture` still owned the event strings/payload.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePermissionDecisionTest" --no-daemon
```

The test passed after adding the factory and delegating through the existing
`LocalTurnTraceCapture` facade method.

## Focused verification

Run before integration:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePermissionDecisionTest" --tests "dev.talos.runtime.ApprovalGatedToolTest" --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --no-daemon
```

## Standard gate

Run before integration:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next move

After T563 lands, inspect the post-T563 local trace evidence shape before
choosing T564. Do not assume checkpoint trace extraction, protected-read
postcondition extraction, action-obligation accounting, trace lifecycle,
trace persistence, prompt-debug lifecycle, or canary scanning is next without
source evidence.
