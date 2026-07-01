# First Run

This page answers: "What am I seeing when Talos starts?"

## Current Support

When started interactively, Talos opens a REPL in the selected workspace and
prints a trusted startup surface unless `--no-logo` is used.

Start from a workspace directory:

```powershell
talos
```

Start with an explicit workspace:

```powershell
talos run --root C:/path/to/workspace
```

## Startup Banner

The startup banner is renderer-owned terminal output. It is not model-authored
text.

It reports:

- Talos version
- workspace
- active mode
- model
- engine
- index state
- policy
- debug state
- next action hint

Typical next action:

```text
ready - type /help, /status, /tools - or ask a question
```

The exact separator glyph depends on terminal capability.

## Prompt

The live input prompt keeps the command name and mode visible:

```text
talos [auto] >
```

The command name stays lowercase because it is an input affordance, not a brand
wordmark.

## Modes

Use `/mode <mode>` inside the REPL to switch mode when supported by the active
runtime.

Public mode names are `auto`, `ask`, `plan`, and `agent`.

- `auto` is the default and routes each turn.
- `ask` is read-only.
- `plan` is read-only planning.
- `agent` can make changes when policy allows and approval is granted.

Legacy aliases `dev`, `chat`, and `unified` resolve to canonical `agent`.
Legacy `rag` remains available as a hidden retrieval-focused compatibility mode.
Reserved `web` is a stub in this build and performs no external network calls.

Use `/status` to see the current active mode and runtime state.

## No-Logo Mode

Skip the full startup surface:

```powershell
talos --no-logo
```

or:

```powershell
talos run --no-logo
```

Talos still prints compact trusted startup information.

## Sensitive Workspace Notice

If the workspace looks sensitive, Talos may print a warning recommending private
mode. Use:

```text
/privacy status
/privacy private on
```

## First Useful Commands

```text
/help
/status
/status --verbose
/workspace
/tools
```
