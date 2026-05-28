# [T565] Extract checkpoint trace recorder

## Summary

T565 extracts checkpoint trace recording from `LocalTurnTraceCapture` into a
package-local `CheckpointTraceRecorder`.

`LocalTurnTraceCapture` remains the public thread-local facade. It still exposes
`recordCheckpoint(...)`, but that method now delegates both checkpoint summary
recording and `CHECKPOINT_*` event recording to `CheckpointTraceRecorder`.

No checkpoint capture policy, checkpoint storage, approval ordering,
fail-closed mutation behavior, protected-read postconditions,
action-obligation accounting, trace lifecycle, trace persistence, prompt-debug
lifecycle, private-document handoff policy, or artifact canary behavior changed.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 2f9d38db
talosVersion = 0.9.9
```

Predecessor:

```text
T564 = Post-permission local trace shape decision
```

## Scope

Moved out of `LocalTurnTraceCapture`:

- checkpoint summary update:
  `LocalTurnTrace.Builder.checkpoint(status, checkpointId)`;
- `CHECKPOINT_*` event type construction;
- checkpoint event payload construction:
  `status`, `checkpointId`, `capturedFiles`, and optional `reason`;
- checkpoint status/id normalization for trace recording;
- stripped reason handling.

Kept in existing owners:

- `TurnProcessor` still owns approval-before-checkpoint-before-mutation order.
- `TurnProcessor` still fails closed before mutation if checkpoint capture
  fails.
- `CheckpointService` still owns config-disabled skip behavior and capture
  facade delegation.
- `CheckpointStore` / `FileBundleCheckpointStore` still own checkpoint storage,
  manifests, file bundle capture, restore behavior, and checkpoint ids.
- `LocalTurnTraceCapture` still owns trace lifecycle, thread-local capture, and
  public facade entry points.

## Behavior preserved

The extracted recorder preserves:

- exact checkpoint summary behavior;
- exact event name prefix: `CHECKPOINT_`;
- blank-status fallback event name: `CHECKPOINT_RECORDED`;
- exact event payload keys and values;
- reason stripping;
- reason omission when blank;
- captured file count behavior;
- timestamp generation at record time;
- raw content exclusion from checkpoint trace events.

## Tests

Added `LocalTurnTraceCheckpointRecorderTest`:

- verifies checkpoint summary status/id are recorded;
- verifies `CHECKPOINT_CREATED` payload shape;
- verifies blank status maps to `CHECKPOINT_RECORDED`;
- verifies blank reason is omitted;
- verifies `LocalTurnTraceCapture` delegates checkpoint recording to
  `CheckpointTraceRecorder`;
- verifies `CheckpointTraceRecorder` owns checkpoint summary update,
  `CHECKPOINT_*` naming, and captured file payload construction.

## RED/GREEN evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCheckpointRecorderTest" --no-daemon
```

The ownership test failed because `CheckpointTraceRecorder.java` did not exist
and `LocalTurnTraceCapture` still owned the checkpoint summary/event write.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCheckpointRecorderTest" --no-daemon
```

The test passed after adding `CheckpointTraceRecorder` and delegating
`LocalTurnTraceCapture.recordCheckpoint(...)` to it.

## Focused verification

Run before integration:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCheckpointRecorderTest" --tests "dev.talos.runtime.TurnProcessorCheckpointTest" --tests "dev.talos.runtime.WorkspaceBatchTurnProcessorTest" --tests "dev.talos.runtime.WorkspaceOperationTurnProcessorTest" --no-daemon
```

## Standard gate

Run before integration:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next move

After T565 lands, inspect the post-T565 local trace evidence shape before
choosing T566. Do not assume protected-read postcondition trace, action
obligation trace, checkpoint capture policy, trace lifecycle, persistence,
prompt-debug lifecycle, or canary scanning is next without source evidence.
