# First run

The Talos REPL starts in `auto` mode. A normal first session should show the workspace, model backend, active model profile, and whether the local engine health check is up.

The goal of the first run is not to prove that every feature works. It is to confirm that Talos is using the intended workspace, the intended model profile, and the installed binary you meant to test.

Useful first commands:

```text
/status
/status --verbose
/help
/mode
/prompt
```

Turn on prompt debugging only when auditing prompt construction:

```text
/debug prompt on
```

After any natural-language turn, inspect trace evidence:

```text
/last trace
```

The trace is local evidence for the most recent turn. It is not tamper-evident and should not be treated as a cryptographic audit trail.

## What to check

- The workspace path is the directory you intended to test.
- The backend and model profile match the configuration you just set up.
- `/mode` lists `auto`, `ask`, `plan`, and `agent`.
- Ask and Plan stay read-only.
- Agent asks before a write.
- `/last trace` reports the mode and evidence for the previous natural-language turn.

If any of these are wrong, stop and fix configuration before using Talos on a real workspace.
