# Local Checkpoint/Restore

Date: 2026-04-29
Status: T36 design for T37 implementation
Parent architecture: `docs/architecture/01-execution-discipline-and-local-trust.md`
Related designs:
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`

## 1. Purpose

Local checkpoint/restore is Talos's restore-point layer for approved file
mutation.

Talos already asks before writing, applies permission policy, records local
trace evidence, and verifies before claiming completion. The missing trust
layer is a first-class way to put the workspace back after an approved mutation
turn goes wrong.

Checkpoint v1 must answer:

- what files were snapshotted before mutation?
- did each file exist before the mutation?
- which turn, trace, and tool call caused the checkpoint?
- did checkpoint creation succeed before mutation?
- can the captured files be restored deterministically?
- what changed during restore?

The checkpoint layer is local-only. It is not cloud backup, source control, or
background autonomy.

## 2. Current State

Talos currently has these related pieces:

- `TurnProcessor` is the central tool execution gateway.
- `DeclarativePermissionPolicy` produces allow/ask/deny decisions before the
  approval gate.
- `ApprovalGate` remains the user interaction seam.
- `LocalTurnTrace` has an empty `CheckpointSummary` placeholder.
- `LocalTurnTrace.Builder.checkpoint(status, checkpointId)` already exists.
- `TurnRecord` can carry a local trace id through session persistence.
- `/last trace` can show local trace information.
- `/undo` uses `FileUndoStack` for the most recent write/edit.

That is useful, but it is not enough:

- `/undo` is a narrow in-memory single-change stack, not a durable per-turn
  restore point.
- There is no persistent checkpoint id.
- There is no checkpoint metadata schema.
- There is no pre-mutation snapshot policy.
- There is no restore command that can restore a whole mutating turn.
- There is no trace-to-checkpoint correlation beyond the placeholder field.

T37 should build on the current trace and permission seams. It should not
replace `/undo` in the same ticket.

## 3. Non-Goals

Checkpoint/restore v1 does not add:

- shell execution
- browser automation
- MCP tools
- cloud backup
- remote upload
- workspace Git requirements
- background daemon behavior
- automatic repair rollback
- enterprise backup policy
- cross-machine sync
- binary document editing support

Checkpoint v1 also does not remove existing approval, permission, sandbox, or
phase checks. It runs after those policies allow a mutation to proceed.

## 4. Design Principles

Checkpoint v1 should be:

- local only
- Windows-first
- deterministic
- bounded to files Talos is about to mutate
- independent of the user's workspace Git state
- correlated with local trace
- conservative on failure
- simple enough to test in unit and e2e scenarios

The model never decides whether checkpointing is required. The runtime decides
from tool risk, permission decision, phase, and config.

## 5. Storage Location

Checkpoint data should live under Talos user data, not inside the workspace.

Recommended default:

```text
%USERPROFILE%\.talos\checkpoints\<workspaceId>\
~/.talos/checkpoints/<workspaceId>/
```

Where `workspaceId` should match the existing
`JsonSessionStore.sessionIdFor(workspace)` behavior or a compatible workspace
hash. It must not require storing the absolute home path in trace output.

Recommended per-checkpoint layout:

```text
~/.talos/checkpoints/<workspaceId>/
  checkpoints/
    <checkpointId>/
      metadata.json
      manifest.json
      blobs/
        <sha256>
        <sha256>
```

This keeps snapshot bytes out of the workspace and allows the local trace to
store only the checkpoint id and summary.

## 6. Backend Choice

The target design is a shadow checkpoint store: Talos owns a local store outside
the workspace and writes restore data into it.

Two backend options are relevant.

### Option A: JDK File-Bundle Backend

This backend uses only Java NIO:

- copy pre-mutation file bytes into content-addressed blob files
- write JSON metadata and a manifest
- record non-existent files so restore can delete files created by Talos
- restore by copying blobs back to workspace paths

Advantages:

- no new dependency
- works in non-Git workspaces
- easy to test on Windows
- matches current file-level tools
- small first implementation

Tradeoffs:

- no native diff/history model
- storage cleanup must be implemented by Talos
- no packfile deduplication beyond simple content hashes

### Option B: JGit Shadow Repository Backend

This backend uses a Talos-owned Git repository outside the workspace:

```text
~/.talos/checkpoints/<workspaceId>/shadow.git
```

Each checkpoint becomes a commit or tree object containing the captured
pre-mutation files and manifest.

Advantages:

- mature content-addressed storage
- built-in deduplication
- commit history maps naturally to checkpoints
- easier future diff/restore inspection

Tradeoffs:

- JGit is not currently in `build.gradle.kts`
- adding JGit requires dependency, size, license, and Qodana review
- Windows path behavior and reserved names need careful tests
- Git concepts may leak into a product that should not require Git knowledge

### Recommendation

T37 should introduce a small `CheckpointStore` interface and may implement the
JDK file-bundle backend first. The metadata schema should remain compatible
with a later JGit shadow-repository backend.

Do not add JGit in T37 unless the implementation ticket explicitly verifies the
dependency and storage tradeoffs. The first user-visible checkpoint behavior is
more important than choosing the final storage engine.

## 7. Proposed Runtime Types

Recommended package:

```text
dev.talos.runtime.checkpoint
```

Recommended v1 classes:

- `CheckpointPolicy`
- `CheckpointDecision`
- `CheckpointStore`
- `CheckpointService`
- `CheckpointRecord`
- `CheckpointManifest`
- `CheckpointFileEntry`
- `CheckpointRestoreResult`
- `CheckpointConfig`

`CheckpointPolicy` answers whether a tool call requires checkpointing.

`CheckpointService` coordinates:

- create turn checkpoint
- capture path before mutation
- attach checkpoint id to trace
- restore checkpoint

`CheckpointStore` owns durable storage.

## 8. Checkpoint Decision

`CheckpointDecision` should include:

- action: `NOT_REQUIRED`, `CREATE`, `USE_EXISTING`, `DENY`
- reason code
- checkpoint id, when one already exists for the turn
- fail-closed flag
- paths to capture for the current tool call
- trace-safe summary

Checkpointing should be considered for mutating tools only:

- `talos.write_file`
- `talos.edit_file`
- future destructive tools

Read-only tools do not require checkpointing.

## 9. Timing

Checkpoint timing must be precise:

1. `TurnProcessor` validates task contract, phase, parameters, sandbox, and
   permission.
2. If permission action is `DENY`, no checkpoint is created.
3. If permission action is `ASK`, the approval prompt runs first.
4. If approval is denied, no checkpoint is created.
5. If permission is `ALLOW` or approval is granted, checkpointing runs before
   the mutating tool executes.
6. The current target path is captured before the tool writes.
7. The mutating tool executes.
8. Verification and outcome rendering run as usual.
9. The checkpoint id is attached to local trace and available through
   `/last trace`.

This ordering matters. Talos should not snapshot files for denied operations,
and it must snapshot before the first byte is changed.

For multiple mutations in one turn, T37 should use one checkpoint id per turn.
Before each mutating tool executes, the checkpoint service should capture that
target if it has not already been captured in the current checkpoint.

## 10. Scope

Checkpoint v1 should capture only concrete file paths Talos is about to mutate.

For `write_file`:

- if the target exists, capture its bytes and metadata
- if the target does not exist, record `existedBefore=false`
- restore should delete the file if it was created by the mutation turn

For `edit_file`:

- capture the target file before editing
- if the file does not exist, the edit should fail before checkpointing or
  record non-existence only if the tool would otherwise create it

For future directory or destructive tools:

- do not implement them in T37
- require a new checkpoint scope review before enabling them

Checkpoint v1 should not snapshot the entire workspace by default. That would
be slow, surprising, and privacy-heavy.

## 11. Metadata Schema

`metadata.json` should be trace-safe and small:

```json
{
  "schemaVersion": 1,
  "checkpointId": "chk_20260429_000001_ab12cd34",
  "workspaceId": "workspace-hash",
  "createdAt": "2026-04-29T12:34:56Z",
  "turnNumber": 18,
  "traceId": "trc_20260429_000018_ab12cd34",
  "taskType": "FILE_EDIT",
  "phase": "APPLY",
  "mode": "auto",
  "model": "qwen2.5-coder:14b",
  "backend": "file-bundle",
  "status": "CREATED",
  "captureReason": "BEFORE_MUTATION",
  "fileCount": 2,
  "byteCount": 8421
}
```

`manifest.json` should contain per-file restore data:

```json
{
  "schemaVersion": 1,
  "checkpointId": "chk_20260429_000001_ab12cd34",
  "files": [
    {
      "relativePath": "index.html",
      "pathHash": "sha256:...",
      "existedBefore": true,
      "blobSha256": "sha256:...",
      "sizeBytes": 4102,
      "lastModifiedTime": "2026-04-29T12:20:01Z",
      "protectedPath": false,
      "protectedKind": "",
      "captureStatus": "CAPTURED"
    },
    {
      "relativePath": "scripts.js",
      "pathHash": "sha256:...",
      "existedBefore": false,
      "blobSha256": "",
      "sizeBytes": 0,
      "lastModifiedTime": "",
      "protectedPath": false,
      "protectedKind": "",
      "captureStatus": "RECORDED_ABSENT"
    }
  ]
}
```

The manifest may include relative paths because checkpoint files are local and
user-owned. Trace output should still prefer checkpoint id, counts, and redacted
path hints.

## 12. Failure Policy

Checkpoint failure must be explicit.

Recommended v1 config:

```yaml
checkpoint:
  enabled: true
  fail_closed: true
  max_file_bytes: 10485760
  max_turn_bytes: 52428800
  retention:
    max_checkpoints_per_workspace: 100
```

If `checkpoint.enabled=true` and `checkpoint.fail_closed=true`, then failure to
create or update the checkpoint must block the mutating tool before execution.

Examples of fail-closed reasons:

- target path cannot be normalized safely
- target escapes workspace
- snapshot read fails
- checkpoint storage cannot be written
- file exceeds configured size limit
- total turn checkpoint exceeds configured size limit

The user-facing message should say:

```text
No file was changed because Talos could not create the required local checkpoint before mutation.
```

If checkpointing is disabled by config, Talos may proceed after permission and
approval, but the trace must record `checkpoint.status = DISABLED`.

## 13. Restore Behavior

Recommended CLI shape:

```text
/checkpoint list
/checkpoint show <checkpointId>
/checkpoint restore <checkpointId>
```

`/restore <checkpointId>` may be added later as an alias, but v1 should avoid
confusing it with `/session load` or `/undo`.

Restore should:

1. load checkpoint metadata and manifest
2. confirm the current workspace id matches the checkpoint workspace id
3. show a concise restore preview
4. require user approval before writing files
5. restore each captured file
6. delete files that were recorded as absent before mutation
7. report per-file restore success/failure
8. write a restore trace or append a restore event to the current local trace

Restore must not silently cross workspaces. If the workspace id does not match,
restore should fail unless a future explicit advanced override is designed.

Restore should be best-effort per file after approval, but the final answer must
report partial restore failures truthfully.

## 14. Permission Interaction

Permission policy remains the authority for whether mutation may proceed.

Ordering:

```text
task contract / phase / parameter validation
-> sandbox/resource checks
-> PermissionPolicy
-> ApprovalGate if ASK
-> CheckpointPolicy / CheckpointService
-> tool execution
```

Protected-path mutation is currently denied before approval by T35. Therefore,
checkpointing will not normally snapshot protected paths for mutation.

If a future permission design allows protected mutation after explicit user
approval, the checkpoint layer must treat protected snapshot content as
sensitive:

- do not print content
- do not include raw values in trace
- consider separate retention and deletion behavior

Session remembered approval must not skip checkpointing. Auto-allowed writes
still require pre-mutation checkpoints when checkpointing is enabled.

## 15. Trace Correlation

`LocalTurnTrace` already has `CheckpointSummary`.

T37 should record:

- `CHECKPOINT_REQUIRED`
- `CHECKPOINT_CREATED`
- `CHECKPOINT_CAPTURED_PATH`
- `CHECKPOINT_FAILED`
- `CHECKPOINT_SKIPPED`
- `RESTORE_STARTED`
- `RESTORE_COMPLETED`
- `RESTORE_FAILED`

Trace summary should include:

- checkpoint status
- checkpoint id
- captured file count
- total captured bytes
- failure reason, if any

Default trace must not store full file contents or full checkpoint manifest.
The trace can point to the checkpoint id and local checkpoint path hint.

## 16. Relationship To `/undo`

`/undo` should remain a fast single-change convenience.

Checkpoint restore is different:

- durable across process restarts
- per-turn or multi-file
- attached to trace
- explicit checkpoint id
- restore preview and approval

T37 should not remove `/undo`. A later UX ticket can decide whether `/undo`
should internally delegate to checkpoint restore once checkpointing is mature.

## 17. Retention And Cleanup

Checkpoint data can grow. T37 should include a simple retention design even if
full cleanup is delayed.

Recommended defaults:

- keep last 100 checkpoints per workspace
- never delete checkpoints from the current turn while Talos is running
- cleanup only checkpoints owned by Talos under `~/.talos/checkpoints`
- do not delete workspace files during cleanup

`/session clear` currently manages session artifacts. A future ticket should
decide whether it also removes checkpoints or whether checkpoint cleanup should
be a separate `/checkpoint clear` command.

## 18. Test Strategy For T37

Unit tests:

- `CheckpointPolicyTest`
  - read-only tools do not require checkpoint
  - mutating tools require checkpoint when enabled
  - disabled checkpoint records skipped decision
  - fail-closed blocks mutation when capture fails

- `FileBundleCheckpointStoreTest`
  - captures existing file bytes
  - records absent file and deletes it on restore
  - rejects workspace escapes
  - restores multiple files
  - preserves binary bytes
  - uses deterministic ids or injected id provider in tests

- `TurnProcessorCheckpointTest`
  - permission denied does not create checkpoint
  - approval denied does not create checkpoint
  - approved write creates checkpoint before mutation
  - remembered approval still creates checkpoint
  - checkpoint failure blocks tool execution when fail-closed

- `LocalTurnTraceCheckpointTest`
  - trace records checkpoint id
  - trace records checkpoint failure without file contents

E2E scenarios:

- approved `write_file` creates checkpoint and writes file
- restore deletes a file created by Talos
- restore restores overwritten file content
- checkpoint failure blocks mutation and final answer does not claim change

Manual test:

1. create a small workspace with `index.html`
2. approve an overwrite
3. verify checkpoint id appears in `/last trace`
4. run `/checkpoint restore <id>`
5. verify original `index.html` content is restored

## 19. Implementation Handoff For T37

Recommended implementation order:

1. Add `dev.talos.runtime.checkpoint` types.
2. Add a JDK file-bundle `CheckpointStore`.
3. Add `CheckpointConfig` parsing from existing `Config`.
4. Wire `CheckpointService` into `TurnProcessor` after approval and before
   mutating tool execution.
5. Record checkpoint summary/events in `LocalTurnTraceCapture`.
6. Add `/checkpoint list/show/restore`.
7. Add unit tests.
8. Add focused e2e scenarios.
9. Run installed manual Talos verification.

Do not add JGit in the same first implementation unless T37 explicitly updates
the dependency plan and verifies the dependency impact.

## 20. Risks

### Over-capturing

Snapshotting the whole workspace would be slow and privacy-heavy. V1 should
capture only files about to be mutated.

### Under-capturing

Capturing only the first file in a multi-file turn would make restore
untrustworthy. V1 should use one checkpoint id per turn and add each target
before its first mutation.

### Sensitive snapshots

Checkpoint blobs may contain sensitive user data. Keep them local, do not print
contents, and avoid storing snapshots in the workspace.

### Session coupling

Checkpoint storage should correlate with sessions and traces but not be
required for normal session replay.

### Dependency creep

JGit may be useful later, but it is not currently in the build. T37 should not
add a large storage dependency without explicit dependency and size review.

## 21. Open Questions

- Should checkpointing be enabled by default immediately in T37, or staged
  behind `checkpoint.enabled=true` for one release?
- Should `/session clear` delete checkpoints, or should checkpoint cleanup be
  separate?
- Should restore itself create a checkpoint before writing restored files?
- How should large files be handled if a user explicitly approves mutation?
- Should checkpoint restore require a second approval even when the original
  mutation was approved for the session?
- Should protected-path snapshots use stricter retention if protected mutation
  is allowed in the future?
