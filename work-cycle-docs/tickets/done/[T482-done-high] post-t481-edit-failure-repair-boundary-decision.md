# [T482-done-high] Post-T481 Edit Failure Repair Boundary Decision

## Status

Done.

## Scope

T482 inspects the post-T481 `ToolCallExecutionStage` shape and decides whether
the next ticket should extract edit-failure repair state, static-web full
rewrite recovery, or another small local helper. This is a no-code decision
ticket.

It does not change runtime behavior, approval behavior, protected/private
handoff behavior, context-ledger behavior, read evidence accounting, mutation
accounting, mutation evidence construction, failure classification, generic
failure state accounting, edit-repair behavior, static-web repair behavior,
trace wording, prompt wording, outcome wording, or final answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `93a90b9d`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 579 lines |
| Architecture baseline | 0 |

## Source Evidence

After T481, generic failure counters, failure-count maps, and failed-mutation
read-cache invalidation live in `ToolFailureStateAccounting`. The remaining
edit failure block in `ToolCallExecutionStage` still owns these responsibilities:

```text
state.failedCallSignatures
state.staleEditRereadIgnoredPath
state.staleEditFailuresByPath
state.emptyEditArgumentFailuresByPath
state.editFailuresByPath
state.cushionFiresE1Suggestion
state.staticWebFullRewriteRequiredTargets
static-web old_string-miss full-write recovery decision
static-web repair trace recording
edit_file multi-failure suggestion wording
```

Relevant current source locations:

- pre-approval stale/empty edit state: `ToolCallExecutionStage.java` lines
  158-163;
- post-result failed edit state: `ToolCallExecutionStage.java` lines 407-430;
- stale/empty helpers: `ToolCallExecutionStage.java` lines 497-505;
- static-web recovery decision and trace: `ToolCallExecutionStage.java` lines
  521-560.

This is not generic failure accounting anymore. It is edit-failure repair state
and bounded repair-routing state.

## Decision

The next correct implementation ticket is:

```text
[T483] Extract edit failure repair state accounting
```

Target owner:

```text
dev.talos.runtime.toolcall.EditFailureRepairStateAccounting
```

Preferred responsibilities:

- record edit pre-approval repair state:
  - set `state.staleEditRereadIgnoredPath` for
    `EditFilePreApprovalGuard.Kind.STALE_REREAD_REQUIRED`;
  - record empty edit argument failures for pre-approval duplicate empty-edit
    blocks;
- record failed `talos.edit_file` post-result repair state:
  - add failed call signatures;
  - record stale edit failures for `old_string not found` after a same-turn
    mutation changed the target;
  - record static-web full-write recovery targets for eligible
    `old_string not found` failures;
  - record empty edit argument failures;
  - update per-path edit failure counts;
  - append the existing multi-failure `talos.write_file` suggestion to the
    returned `ToolResult` without changing wording;
  - increment `state.cushionFiresE1Suggestion` exactly when the stage does
    today;
  - return a small result carrying the possibly adjusted `ToolResult`.

`ToolCallExecutionStage` should keep:

- when edit repair accounting is invoked;
- calling `EditFilePreApprovalGuard`;
- generic failure accounting through `ToolFailureStateAccounting`;
- applying denial/path-policy/approval flags;
- `ToolOutcome` construction;
- tool-result message formatting;
- iteration-local counters and outcome assembly.

## Why This Slice Is Correct

The remaining block has one coherent reason to exist: failed `edit_file` calls
create repair state that later controls duplicate-edit suppression, stale-read
repair prompts, empty-edit repair prompts, static-web full-file recovery, and
the existing repeated-edit suggestion. Those are linked by the same failed edit
event and the same normalized path.

Splitting only a tiny helper would reduce line count while leaving ownership
confusion in place. Moving all repair prompts would be too broad because
`ToolCallRepromptStage`, `RepairPolicy`, target-readback repair, expected-target
repair, and static-web continuation have separate responsibilities.

## Rejected Immediate Work

### Extract static-web full rewrite recovery alone

Rejected for T483.

The static-web full rewrite path is triggered by the same failed edit event and
shares the same `old_string not found` classification, same path, and same
repair state update surface. Extracting only this piece would leave the failed
edit state split across two owners.

### Extract only failed call signatures

Rejected for T483.

That would be a mechanical helper extraction. It would not fix the ownership
problem because stale edit state, empty edit state, static-web recovery, and
multi-failure suggestion state would remain in the stage.

### Move repair prompt selection

Rejected for T483.

Prompt selection and compact repair planning are reprompt-stage responsibilities.
Moving them together with post-result failed-edit state would risk behavior and
wording changes.

## Required T483 Tests

Start with RED tests for `EditFailureRepairStateAccounting`:

- pre-approval stale reread decision records `state.staleEditRereadIgnoredPath`;
- pre-approval duplicate empty edit records a normalized empty-edit failure;
- failed edit records the failed call signature;
- `old_string not found` after a same-turn mutation records stale edit failure;
- eligible static-web `old_string not found` records a full-write recovery
  target without moving static-web prompt selection;
- empty edit arguments record empty edit failures;
- repeated failed edits append the existing `talos.write_file` suggestion
  without changing wording and increment `state.cushionFiresE1Suggestion`;
- `ToolCallExecutionStage` delegates edit failure repair state accounting and
  no longer owns `recordEmptyEditArgumentFailure(...)`,
  `recordStaleEditFailure(...)`,
  `shouldRecoverStaticWebEditFailureWithFullRewrite(...)`, or
  `recordStaticWebFullRewriteRequired(...)`.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFailureRepairStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --tests "dev.talos.runtime.toolcall.ToolFailureStateAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*emptyEdit*" --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*staticWebFullRewrite*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
