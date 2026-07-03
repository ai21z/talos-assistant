# [T927-open-medium] Session clear target mismatch

Status: open
Priority: medium

## Evidence Summary

- Source: WSL2 Ubuntu installed-product REPL smoke
- Date: 2026-07-02
- Talos version / commit: 0.10.7 /
  2314360f2f972d482405437581160436b456c939
- Platform: WSL2 Ubuntu 26.04, installed Linux Talos under
  `/home/ai21z/.local/talos`
- Model/backend: managed `llama.cpp` / `qwen2.5-coder-14b`

Observed behavior:

```text
saved session found: 1 prior exchange from 18m ago. Not loaded. Use /session load to resume or /session clear to delete.

talos [auto] > /session clear

  i No saved session to delete.
```

Expected behavior:

```text
If startup says `/session clear` will delete the saved session it just
reported, then `/session clear` should delete that target or the startup notice
should name the command that actually manages that target.
```

Code evidence:

- `TalosBootstrap.create(...)` mints a fresh per-run active session id with
  `JsonSessionStore.newSessionInstanceId(...)`.
- The startup saved-session notice inspects
  `latestSessionId(sessionStore, workspaceId)`, which is the latest stored
  session for the workspace, not necessarily the fresh active session.
- `SessionCommand.clear()` calls `store.delete(activeSessionId)`, deleting only
  the active session for the current process.
- `JsonSessionStore.listSessions(...)` shows multiple previous instance files
  can coexist for one workspace.

Disk evidence from the WSL machine confirmed multiple prior
`~/.talos/sessions/<workspace-hash>-<timestamp>` files existed. The new active
session was empty when `/session clear` was run, so the command returned "No
saved session to delete" while the banner had referred to an older stored
session.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `UX`
- `AUDITABILITY`

Severity: P2

Why this level:

```text
This is not session corruption and not a protected-data failure. It is a
command-target truth bug: the user is told one command deletes the visible saved
session, but that command targets the fresh current session instead.
```

## Goal

```text
Make the startup saved-session notice and `/session clear` semantics agree.
Users must be able to predict whether they are deleting the current run's
session, the latest previous session, or all workspace sessions.
```

## Non-Goals

- No auto-loading saved sessions.
- No default deletion of all historical sessions without an explicit command or
  confirmation.
- No deletion of trace artifacts beyond the session target chosen by the user.
- No change to the privacy/redaction rules for saved session content.

## Fix Direction

Preferred minimal fix:

```text
Change the startup notice so it no longer says `/session clear` deletes the
reported previous session. Point to `/session load` to resume and `/session
list` to manage saved sessions.
```

Alternative broader fix:

```text
Add explicit target forms such as `/session clear current`, `/session clear
latest`, or `/session clear <id-prefix>`, with confirmation for non-current
targets. This is more useful but touches more behavior and requires stronger
tests.
```

## Acceptance Criteria

- Startup notice does not imply `/session clear` deletes a previous saved
  session unless that command actually does.
- `/session clear` output clearly says whether it targeted the active/current
  session.
- `/session list` remains the discovery surface for older stored sessions.
- Tests cover the exact mismatch: prior stored session exists, new active
  session has no saved files, startup notice is rendered, `/session clear` is
  invoked.
- No saved session is loaded into prompt context by default.

## Tests / Evidence

Required focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.TalosBootstrapTest" --tests "dev.talos.cli.repl.slash.SessionCommandTest" --no-daemon
git diff --check
```

If implementation touches broader session behavior, also run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.JsonSessionStore*Test" --no-daemon
```

## Work-Test Cycle Notes

- This ticket is deliberately separated from T926. T926 owns setup/onboarding;
  this ticket owns a session-command truth mismatch exposed during the WSL
  smoke.
- This can be fixed before or after T926 milestone 1. It should not block the
  setup wizard dry-run unless the wizard starts relying on session commands.

