# Approvals And Permissions

This page answers: "When does Talos ask before doing something?"

## Current Support

Talos uses approval prompts for sensitive local actions. The approval prompt is
runtime-owned terminal UI, not model-authored text.

Common approval choices:

```text
y = approve once
a = approve for session
Enter = deny
```

Some prompts are one-turn-only and do not offer session approval.

## Actions That Commonly Require Approval

| Action family | Expected behavior |
| --- | --- |
| File write | ask before writing |
| File edit | ask before editing |
| Delete/remove | ask and show destructive risk |
| Move/rename/copy/mkdir | ask before workspace mutation |
| Protected read | ask before sensitive inspection |
| Command execution | ask through configured command profile |

Approval does not mean "unbounded." A path or command can still be denied by
policy.

## Denial

Pressing Enter, sending EOF, or entering anything other than an accepted approve
response denies the action.

Denied actions are expected to leave the workspace unchanged.

## Session Approval

When `a` is offered, it means "approve for this session" for the relevant
approval category. It is not a permanent config change.

Use session approval carefully. Prefer one-time approval when reviewing a risky
target.

## Protected Reads

Protected paths and sensitive-looking files are treated differently from
ordinary workspace files.

In developer/default mode, an approved protected read may enter model context
for that turn.

In private mode, approved protected reads default to local-display-only:
content is read locally after approval but withheld from model context and raw
persisted artifacts unless explicit config opt-ins are enabled.

Use:

```text
/privacy status
/privacy private on
```

## Command Execution

Commands run through profiles instead of arbitrary shell strings. The current
model-callable command surface exposes Gradle verification profiles:

- `gradle_test`
- `gradle_check`
- `gradle_build`
- `gradle_install_dist`
- `gradle_e2e_test`

Unknown profiles are rejected. Non-Gradle diagnostic profiles may exist inside
the runtime registry, but they are not a current user-facing command execution
promise.

Command working directories must stay inside the workspace.
