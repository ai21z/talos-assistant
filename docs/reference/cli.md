# CLI reference

Use `talos --help` for the authoritative command list in the installed build.

This reference names the command families a beta tester normally needs. It does not replace installed help, because CLI output must match the exact artifact under test.

Common command families:

| Command | Use |
|---|---|
| `talos` | Start the REPL in the current workspace. |
| `talos status --verbose` | Print workspace, index, backend, model, and health state. |
| `talos doctor --start` | Validate config, files, engine, model smoke, and local writable paths. |
| `talos setup wizard` | Guided setup. Published 0.10.8 automatic engine installation is limited to Ubuntu/WSL. Windows engine installation is Unreleased. |
| `talos setup models` | Print model setup help. Writes require `--profile`, `--server-path` and `--write`. |

The installed command should be tested after a clean install, not only through the Gradle development classpath.

## REPL commands

Inside `talos`, use `/help` and `/help all` for the current slash-command list. The most useful audit commands are `/status --verbose`, `/mode`, `/prompt`, `/debug prompt on`, and `/last trace`.

| REPL command | Use |
|---|---|
| `/profiles list` | List configured verification profiles. |
| `/profiles configure <id> --exec <executable>` | Configure a profile using an actual ID and executable. |
| `/profiles trust` | Review and pin the current workspace profile declaration. |
| `/profiles revoke` | Revoke the declaration's trust. |
| `/verify [ws:<id>]` | List runnable profiles, or run the named trusted workspace profile after approval. |

Commands that expose prompt or trace details are audit tools. They are intentionally useful for review, but they are not the normal product path for every user.
