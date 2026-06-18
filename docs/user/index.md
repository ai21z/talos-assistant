# Talos User Documentation

Talos is a local-first CLI workspace operator. It is strongest when the work is
bounded to a selected workspace, the task can be inspected through local files,
and mutations can be approved and verified.

Local-first depends on configuration. The chat transport does not yet enforce a
localhost-only guard, so review [Model Setup](model-setup.md) and
[Local Privacy And Artifacts](local-privacy-and-artifacts.md) before using a
remote `ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or
`TALOS_ENGINE_HOST`.

These pages are for users. They avoid ticket history, audit runbooks, and
implementation design notes. Internal engineering material still exists
elsewhere in the repository, but it is not the normal path for learning Talos.

## Start Here

| Need | Read |
| --- | --- |
| Run Talos for the first time | [Quickstart](quickstart.md) |
| Understand install status | [Installation](installation.md) |
| Configure a local model | [Model Setup](model-setup.md) |
| Understand the first terminal screen | [First Run](first-run.md) |
| Learn workspace and index behavior | [Workspaces And Indexing](workspaces-and-indexing.md) |
| Learn the execution discipline | [How Talos Works](how-talos-works.md) |
| Understand approvals | [Approvals And Permissions](approvals-and-permissions.md) |
| Understand privacy and local artifacts | [Local Privacy And Artifacts](local-privacy-and-artifacts.md) |
| Check file type support | [File Support](file-support.md) |
| Look up commands | [Commands](commands.md) |
| Diagnose failures | [Troubleshooting](troubleshooting.md) |
| Understand beta release channels | [Release Channels](release-channels.md) |

## Current Beta Boundary

Read the current user-facing docs with these limits:

- Talos is Windows-first in the current beta path.
- Public package-manager installation is planned, not live.
- The current reliable install path is source/developer setup.
- The planned public installer does not include model weights or a llama.cpp
  server.
- Local model setup is explicit and user-controlled.
- Talos is not positioned for private paperwork such as tax, health, legal,
  family, or administrative folders.

## Documentation Rules

Each user page follows the same discipline:

- State the current behavior before planned behavior.
- Show commands users can actually run.
- Pair capabilities with limits.
- Keep internal implementation details out of the main explanation.
- Mark unsupported or planned behavior honestly.
