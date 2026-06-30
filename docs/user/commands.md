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
| `/profiles` | Inspect, configure, trust, or revoke workspace verification profiles in `.talos/profiles.yaml`. |
| `/verify ws:<id>` | Run a trusted workspace verification profile after approval. |
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
Built-in profiles cover Talos' own Gradle build and Gradle workspaces:

- `gradle_test`
- `gradle_check`
- `gradle_build`
- `gradle_install_dist`
- `gradle_e2e_test`

Maven workspaces are supported through trusted workspace verification profiles,
not by changing Talos' own build from Gradle to Maven and not by adding an
arbitrary shell escape. Declare the fixed Maven command in
`.talos/profiles.yaml`, review and trust the declaration, then run it by id.

Recommended Maven verification profile:

```yaml
profiles:
  - id: maven_verify
    executable: ./mvnw
    args: ["-B", "--no-transfer-progress", "verify"]
    timeout_ms: 600000
    expected_writes: ["target/"]
```

Equivalent REPL configuration flow:

```text
/profiles configure maven_verify --exec ./mvnw --arg -B --arg --no-transfer-progress --arg verify --timeout-ms 600000 --expected-write target/
/profiles trust
/verify ws:maven_verify
```

On Windows Maven workspaces, use `./mvnw.cmd` in the profile if that is the
wrapper file present in the project. Workspace profiles use declared fixed argv
only: callers cannot append ad hoc Maven arguments at run time, and every run
still asks for approval.

Unknown profiles are rejected. Untrusted or changed `.talos/profiles.yaml`
declarations fail closed before approval until `/profiles trust` pins the new
SHA-256. A successful trusted `ws:maven_verify` run counts as command
verification evidence only when the command exits 0.

`run_command` stdout and stderr pass through the model-context handoff
boundary. Non-sensitive command output remains visible to the model for
verification answers; command output that required secret redaction is withheld
from model context and replaced with a bounded notice. This is not a complete
command-output privacy proof. Do not run commands that print real credentials in
this beta.

Maven may access the network while resolving dependencies and may write to the
local Maven cache outside the workspace. Talos' workspace profile constrains the
argv it launches; it does not make Maven offline unless the workspace's Maven
configuration already does that.
