# Commands

Use this page for orientation. The installed `talos --help` and `/help all` output are the authority for the exact command list in the build you are running.

Top-level CLI commands start Talos, inspect runtime state, configure local models, and run configured verification profiles:

```text
talos
talos --version
talos status
talos status --verbose
talos doctor
talos doctor --start
talos setup wizard
talos setup models
talos profiles list
talos profiles configure
talos profiles trust
talos verify
```

Common REPL slash commands:

```text
/help
/help all
/mode
/mode ask
/mode plan
/mode agent
/status
/status --verbose
/doctor
/prompt
/debug prompt on
/last trace
/session clear
```

`talos doctor` checks configuration and local runtime state. `talos doctor --start` may start the managed model server, require an end-to-end model smoke reply, then release the managed server again.

Mutation and command execution are not implicit. Talos asks before writes and before approved command profiles run.

## Useful command order

For a clean workspace smoke, use:

```text
talos status --verbose
talos doctor --start
talos
/debug prompt on
/status --verbose
/mode ask
What is in this workspace?
/last trace
```

Use `/prompt` and prompt-debug commands for audits, not for normal first use.
