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
model-callable command surface exposes built-in Gradle verification profiles:

- `gradle_test`
- `gradle_check`
- `gradle_build`
- `gradle_install_dist`
- `gradle_e2e_test`

Workspace-specific verification commands use `ws:<id>` profiles declared in
`.talos/profiles.yaml`. This is the current Maven support path: declare a fixed
Maven wrapper command such as `./mvnw -B --no-transfer-progress verify`, trust
the declaration with `/profiles trust`, and run it with `/verify ws:maven_verify`.

```text
/profiles configure maven_verify --exec ./mvnw --arg -B --arg --no-transfer-progress --arg verify --timeout-ms 600000 --expected-write target/
/profiles trust
/verify ws:maven_verify
```

The declaration is not trusted merely because it exists. `/profiles trust`
reviews and pins the current `.talos/profiles.yaml` SHA-256. If the file changes
later, the profile returns to untrusted and cannot reach an approval prompt
until it is reviewed and trusted again.

Workspace profiles always use declared fixed argv only. The model or user turn
cannot append arbitrary Maven arguments to `ws:maven_verify` at execution time,
and every `/verify` or `talos.run_command` execution still requires approval.

Unknown profiles are rejected. Non-Gradle diagnostic profiles may exist inside
the runtime registry, but they are not a current user-facing command execution
promise.

Command working directories must stay inside the workspace.

Maven is not automatically offline. Dependency resolution may use the network
and may write to a local Maven cache outside the workspace unless the project or
environment has already configured Maven otherwise. Treat the profile as a
bounded command-launch policy, not as a guarantee about Maven's own dependency
behavior.
