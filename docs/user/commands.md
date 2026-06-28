# Commands

This page answers: "Which Talos commands do I use?"

## Current Support

Talos has top-level CLI commands and REPL slash commands.

Show top-level help:

```powershell
talos --help
```

Show REPL help:

```text
/help
/help all
```

## Top-Level CLI

| Command | Use |
| --- | --- |
| `talos` | Start the interactive REPL in the current directory. |
| `talos run` | Start the interactive REPL with run options. |
| `talos --version` | Print version information. |
| `talos version` | Print version information. |
| `talos status` | Show current workspace/config status. |
| `talos status --verbose` | Show diagnostics, config path, engine health, and user config status. |
| `talos setup` | Show setup summary. |
| `talos setup models` | Show managed model setup help. |
| `talos diagnose -q "<question>"` | Diagnose RAG configuration and prompt sizing. |
| `talos rag-index` | Build or update the workspace index. |
| `talos rag-ask "<question>"` | Ask a retrieval-backed question. |
| `talos net` | Show the effective network policy. |

## Common REPL Commands

| Command | Use |
| --- | --- |
| `/help` | Show help. |
| `/status` | Show trusted status dashboard. |
| `/status --verbose` | Show detailed diagnostics. |
| `/workspace` | Show workspace information. |
| `/files` | List indexed files. |
| `/grep` | Search workspace text. |
| `/show` | Show an indexed snippet or small workspace file. |
| `/reindex` | Rebuild or update index. |
| `/tools` | List AI-callable tools. |
| `/models` | List models visible to the engine catalog. Managed `llama.cpp` shows the configured/running GGUF, not every downloaded cache entry. |
| `/set model <backend/model>` | Switch among visible active/catalog models. For managed GGUF profile changes, use `talos setup models ... --write --force` and restart. |
| `/mode <mode>` | Switch mode; public modes are `auto`, `ask`, `plan`, and `agent`. Legacy `dev`, `chat`, and `unified` resolve to `agent`; legacy `rag` remains hidden but selectable. Reserved `web` performs no external network calls in this build and cannot be selected. |
| `/privacy status` | Show privacy settings. |
| `/privacy private on` | Enable private mode. |
| `/last trace` | Show evidence from the last turn. |
| `/session info` | Show session state. |
| `/session clear` | Clear session state. |
| `/clear` | Reset conversation context. |
| `/q` | Exit. |

## Debug And Audit-Oriented Commands

These exist, but they are not the normal first path for users:

- `/debug`
- `/prompt`
- `/prompt-debug`
- `talos prompt-render`
- `/audit`
- `/bench`
- `/secret`
- `/checkpoint`
- `/undo`
- `/route`
- `/memory`
- `/k`

Use them when diagnosing, auditing, or following maintainer guidance.

## Command Profiles

Talos command execution uses profiles rather than arbitrary shell execution.
The current model-callable command tool exposes these Gradle verification
profiles:

- `gradle_test`
- `gradle_check`
- `gradle_build`
- `gradle_install_dist`
- `gradle_e2e_test`

Unknown profiles are rejected. The runtime has additional internal diagnostic
profile definitions, but normal user docs treat Gradle verification as the
current command execution surface.

`run_command` stdout and stderr pass through the model-context handoff
boundary. Non-sensitive command output remains visible to the model for
verification answers; command output that required secret redaction is withheld
from model context and replaced with a bounded notice. This is not a complete
command-output privacy proof. Do not run commands that print real credentials in
this beta.
