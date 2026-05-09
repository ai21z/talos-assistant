# Talos

Talos is a local-first CLI workspace assistant with retrieval,
approval-gated file operations, traces, context handling, and
verification-oriented outcomes.

It runs as an execution harness for local workspace work. Talos can inspect
files, list directories, search a workspace, retrieve local indexed context,
use local tools, ask for approval before writes or bounded commands, preserve
local turn traces and session context, and report what was verified.

Talos started as LOQ-J, a local RAG CLI, and evolved into a broader local-first
workspace assistant. Retrieval remains part of Talos, but it is no longer the
whole product.

The public release version is defined in `gradle.properties` as `talosVersion`, so the build and CLI stay aligned.

## Current Status

Talos is under active beta hardening. It currently focuses on bounded local
workspace tasks with explicit user control, not unattended background
automation.

## What Talos Is Not

Talos is not:

- a foundation model
- a cloud-agent clone
- a swarm
- a background autonomous daemon
- just a RAG CLI

## Practical Limits

Talos is useful today, but the trust layers are still being hardened.

- local model quality matters
- setup and hardware matter
- not all file types are supported equally
- not every task can be semantically verified
- the project is still evolving

## Talos In One Minute

Talos is built for a simple local workflow:

- point it at a workspace
- let it inspect, retrieve, and reason over that workspace
- allow safe read-only operations automatically
- require approval before write operations
- preserve local traces and session context
- report verification-oriented outcomes when checks are available
- keep the whole loop local on your machine

If you want the shortest accurate description, it is this:

> Talos is a local-first CLI workspace assistant for understanding and changing a workspace, with retrieval, tools, approval gates, traces, context handling, and verification-oriented outcomes.

## How A Turn Works

One Talos turn is not just "prompt in, paragraph out".

```text
    .--------------------.
    | inspect workspace  |
    '---------+----------'
              |
              v
    .--------------------.
    | retrieve context   |
    | when needed        |
    '---------+----------'
              |
              v
    .--------------------.
    | call local tools   |
    | when the task      |
    | needs real action  |
    '---------+----------'
              |
              v
    .--------------------.
    | report, trace,     |
    | and persist turn   |
    '--------------------'
```

In practice, a turn can include:

- file reads
- directory listing
- grep-style search
- retrieval from the local index
- write or edit operations with approval
- session-memory updates
- trace persistence
- verification-oriented completion checks
- persistence to session artifacts

That is why calling Talos only a "RAG CLI" is misleading.

## What Talos Does Today

At a high level, Talos currently has five main jobs:

1. Understand a workspace
2. Retrieve relevant local context
3. Use tools to inspect or change files
4. Keep a session coherent across turns
5. Preserve traceable outcomes for review

### Workspace understanding

Talos can answer questions about the current project, inspect specific files, list directories, search for patterns, and summarize what it finds.

### Retrieval

Talos still has a real indexing and retrieval path.

- `rag-index` builds the local index
- `rag-ask` asks through the retrieval pipeline directly
- the unified assistant can also use retrieval as a tool when it needs workspace context

So retrieval remains important, but it now sits inside a larger assistant architecture.

### Tool use

Talos has a small tool set focused on local workspace work:

| Tool | Purpose | Approval |
|---|---|---|
| `read_file` | read a file with line-oriented output | not required |
| `list_dir` | inspect workspace structure | not required |
| `grep` | search for patterns in the workspace | not required |
| `retrieve` | pull relevant indexed context | not required |
| `write_file` | create or replace file content | required |
| `edit_file` | patch file content by targeted replacement | required |
| `mkdir` | create a directory inside the workspace | required |
| `copy_path` | copy a file or directory inside the workspace | required |
| `move_path` | move a file or directory inside the workspace | required |
| `rename_path` | rename a file or directory inside its parent | required |
| `apply_workspace_batch` | apply a small approved batch of workspace operations | required |
| `run_command` | run approved bounded command profiles | required |

Write tools are intentionally gated. The user stays in control of the workspace.

### Workspace boundary

Talos works inside the workspace selected when the session starts. Natural
requests such as creating files, creating folders, copying paths, or running
approved checks are scoped to that workspace.

Talos does not currently change workspace inside an active session. The
`/workspace` command is informational: it shows the current workspace and index
paths, but it does not switch to another folder. To work somewhere else, start
Talos from the folder you want to use.

### Session behavior

Talos now has real session behavior, not just stateless one-shot answers.

- conversation history is kept in memory
- sessions are persisted locally
- turn logs are written for durability
- prior session state can be restored for the same workspace

## The Main User Modes

Talos exposes multiple modes, but the most useful mental model is simple:

- `auto`: default and recommended for most work
- `rag`: explicit retrieval-focused mode
- `dev`: deterministic file/navigation commands
- `ask` and `chat`: direct assistant-style interaction
- `web`: reserved, not a full web mode in this build

Auto mode is assistant-first. It uses tools and retrieval when needed instead of forcing the user to think in separate subsystems.

## Quick Start

### 1. Install prerequisites

What you need right now:

- Java 21+
- Ollama running locally
- a local chat model in Ollama
- an embeddings model in Ollama if you want vector retrieval

Recommended Ollama pulls:

```powershell
ollama pull qwen3:8b
ollama pull bge-m3
```

### 2. Build Talos

```powershell
.\gradlew.bat installDist
```

### 3. Install on Windows

```powershell
pwsh tools\install-windows.ps1
```

### 4. Run Talos

```powershell
talos
```

### 5. Build an index for a workspace when needed

```powershell
talos rag-index
```

### 6. Ask something useful

```text
What does this project do?
Read README.md and explain the architecture.
Change only the page title in index.html.
```

## Common Commands

### Top-level CLI

| Command | Purpose |
|---|---|
| `talos` | start the interactive REPL |
| `talos run` | explicit REPL entry |
| `talos rag-index` | build or refresh the local index |
| `talos rag-ask "..."` | ask through the retrieval lane directly |
| `talos status` | inspect current workspace/config state |
| `talos diagnose` | inspect retrieval and answer-generation behavior |
| `talos version` | print version information |
| `talos setup` | first-run setup flow |

### Useful REPL commands

| Command | Purpose |
|---|---|
| `/help` | show commands |
| `/mode <mode>` | switch active mode |
| `/models` | list available models |
| `/set model <backend/model>` | switch active model |
| `/reindex` | rebuild the current workspace index |
| `/workspace` | show current workspace status; does not switch workspace |
| `/status` | show runtime and indexing details |
| `/tools` | show the registered tool set |
| `/session info` | inspect current session state |
| `/clear` | clear conversation memory |
| `/q` | exit |

## The Talos Work Cycle

Talos now has a clearer work cycle for development and review.

There are two loops:

- a fast inner development loop
- a slower versioned candidate loop

```text
    change code
         |
         v
    .----------------------.
    | versioned candidate  |
    '----------+-----------'
               |
               v
    build -> test -> e2e -> coverage -> qodana -> review
               ^                                        |
               |                                        |
               '-------- change code if needed ---------'
```

The short version:

- iterate quickly while implementing
- bump patch version only when you want a real review candidate
- build evidence for that candidate as one unit

The full work-cycle writeup lives here:

- [work-cycle-docs/work-test-cycle.md](work-cycle-docs/work-test-cycle.md)
- [work-cycle-docs/work-test-cycle-setup.md](work-cycle-docs/work-test-cycle-setup.md)
- [work-cycle-docs/work-test-cycle-step-by-step.md](work-cycle-docs/work-test-cycle-step-by-step.md)

Post-0.9.6 architecture direction is documented in
[docs/architecture/01-execution-discipline-and-local-trust.md](docs/architecture/01-execution-discipline-and-local-trust.md).

## What You Need To Run Talos Well

### Hardware

Talos can run on modest hardware, but better local models need more RAM.

Practical guidance:

- small local models: comfortable on typical developer machines
- larger local models: more RAM and patience required
- SSD strongly recommended for smoother indexing and local model work

### Software

Current practical setup is:

- Windows is the most supported day-to-day path in this repo
- Java 21+
- Ollama on the same machine

### Network expectations

Talos is local-first.

- your workspace data is intended to stay local
- Talos talks to Ollama over localhost
- you still need to download models ahead of time

## Quality Reports

Talos can generate reviewer-friendly Markdown quality reports from the machine-readable summaries in `build/reports/talos/`.

Use this when you want local snapshots for coverage, E2E, Qodana, and build artifact provenance:

```powershell
./gradlew.bat writeQualityMarkdownReports
```

For a full fresh local quality run that refreshes native Qodana first, use:

```powershell
./gradlew.bat talosQualityLocal
```

Reports are written to the repository-root `reports/` folder using this format:

```text
<reportName>-DDMMYYYY-<talosVersion>.md
```

Example:

```text
coverage-23042026-090.md
```

The generated `reports/` folder is intentionally ignored by Git. The tracked `reports-disabled/README.md` explains how to use it: either create `reports/`, or rename/copy `reports-disabled/` to `reports/`. Gradle will also create `reports/` automatically when the report task runs.

Before writing new reports, the generator removes older generated report snapshots with the standard report filename pattern. Manual files with other names are preserved.

## Current Limitations

This is the honest part.

Talos is improving, but it still has clear limits:

- Windows is the best-supported operational path right now
- the current engine path is centered on local Ollama usage
- web mode is not a full browsing product in this build
- local model quality still matters a lot for editing and diagnosis quality
- setup and hardware affect latency, context size, and model choices
- not all file types are supported equally
- not every task can be semantically verified
- trust layers are still being hardened
- retrieval, tools, and session behavior are stronger than they were, but still evolving

If you need a one-line status:

> Talos is useful for local workspace understanding and guarded file operations, but it is still under active beta hardening.

## Repo Layout

High-level layout:

```text
.
|-- src/                 Java source
|-- docs/                tracked project and architecture docs
|-- scripts/             helper scripts
|-- tools/               install and support tooling
|-- local/               ignored local working space
|-- reports-disabled/    tracked docs for ignored local reports
|-- build/               generated outputs
|-- CHANGELOG.md         human-readable version history
`-- README.md            project overview
```

The `local/` folder is for personal workspace material on this machine, including manual-testing notes. It is intentionally ignored by Git. Generated `reports/` are also ignored; keep only usage instructions in `reports-disabled/`.

## Repository Identity Migration

Current public identity:

- Product name: Talos
- Repository name: `talos-cli`
- GitHub repository: `ai21z/talos-cli`
- GitHub URL: `https://github.com/ai21z/talos-cli`
- SSH URL: `git@github.com:ai21z/talos-cli.git`
- Public description: "Local-first CLI workspace assistant with retrieval, approval-gated file operations, traces, context handling, and verification-oriented outcomes."

Repository rename checklist:

- Rename GitHub repository to `ai21z/talos-cli` through the GitHub UI.
- Update local git remote:
  ```powershell
  git remote set-url origin https://github.com/ai21z/talos-cli.git
  ```
- Verify local remote:
  ```powershell
  git remote -v
  ```
- Update README links.
- Update install docs.
- Update scripts with hardcoded repo URLs.
- Update docs and examples.
- Update screenshots or captions if they mention old names.
- Verify old GitHub links redirect.
- Do not create a new repository using the old `loqj-cli` name, because it can interfere with GitHub redirects.

Suggested GitHub topics:

- `local-ai`
- `cli`
- `java`
- `ollama`
- `workspace-assistant`
- `ai-agent`
- `local-first`
- `retrieval`
- `developer-tools`
- `verification`

## Bottom Line

Talos should now be understood like this:

- not just a RAG CLI
- not just a chat shell
- not just a file editor

It is a local-first workspace assistant and execution harness that combines:

- retrieval
- local tools
- approval-gated file operations
- local traces
- context handling
- verification-oriented outcomes
- developer-oriented CLI workflows

That is the current state of Talos.
