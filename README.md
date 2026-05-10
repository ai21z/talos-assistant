# Talos

Talos is a local-first CLI workspace assistant for understanding and changing a
developer workspace through governed local tools, approval gates, traces,
context handling, and verification-oriented outcomes.

Talos began as LOQ-J, a local RAG CLI. It has evolved into a broader local
workspace assistant and execution harness. Retrieval remains part of the
system, but it now sits beside file tools, workspace operations, bounded command
profiles, session state, prompt-debug evidence, and local trace records.

The public release version is defined in `gradle.properties` as
`talosVersion`, so the build and CLI stay aligned.

## Current Status

Talos is under active beta hardening. The current beta path focuses on bounded
local workspace tasks, explicit user control, local model execution, and
auditable outcomes.

The preferred model backend for the current product path is managed
`llama.cpp`. Ollama remains available as a legacy backend option.

## How A Turn Works

A Talos turn is handled as an execution cycle:

```text
    .--------------------.
    | classify request   |
    '---------+----------'
              |
              v
    .--------------------.
    | inspect workspace  |
    | or retrieve context|
    '---------+----------'
              |
              v
    .--------------------.
    | call allowed tools |
    | when action is     |
    | required           |
    '---------+----------'
              |
              v
    .--------------------.
    | verify, trace,     |
    | and report outcome |
    '--------------------'
```

In practice, a turn can include:

- file reads
- directory listing
- grep-style search
- retrieval from the local index
- approved file creation and edits
- approved workspace operations such as mkdir, copy, move, and rename
- approved bounded command profiles
- session-memory updates
- prompt-debug and trace persistence
- verification-oriented completion checks

Runtime policy decides which tools are visible for the current turn. Mutation
tools are exposed only for apply-oriented turns, and command execution is exposed
only for approved command or verification turns.

## What Talos Does Today

Talos currently supports five main workflows:

1. Understand a local workspace.
2. Retrieve relevant local context.
3. Inspect, create, and modify workspace files through approved tools.
4. Keep a local session coherent across turns.
5. Preserve traceable outcomes for review.

### Workspace Understanding

Talos can answer questions about the current project, inspect specific files,
list directories, search for patterns, and summarize evidence from the
workspace.

### Retrieval

Talos has a local indexing and retrieval path:

- `rag-index` builds the local index.
- `rag-ask` asks through the retrieval pipeline directly.
- The unified assistant can use retrieval as a tool when workspace context is
  needed.

### Tool Use

Talos has a focused tool set for local workspace work:

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

Write tools are approval-gated. The workspace remains under user control, and
Talos records the outcome of each governed operation.

### Workspace Boundary

Talos works inside the workspace selected when the session starts. Natural
requests such as creating files, creating folders, copying paths, or running
approved checks are scoped to that workspace.

The `/workspace` command shows the current workspace and index paths. To work in
a different folder, Talos should be started from that folder.

### Session Behavior

Talos maintains local session state:

- conversation history is kept in memory
- sessions are persisted locally
- turn logs are written for durability
- prior session state can be restored for the same workspace
- prompt-debug and trace artifacts can be reviewed when debugging behavior

## Main User Modes

Talos exposes multiple modes:

- `auto`: default mode for most workspace work
- `rag`: explicit retrieval-focused mode
- `dev`: deterministic file and navigation commands
- `ask` and `chat`: direct assistant-style interaction
- `web`: reserved mode in this build

Auto mode is assistant-first. It uses tools and retrieval when needed, while
runtime policy keeps each turn bounded.

## Quick Start

### 1. Install prerequisites

Current practical setup:

- Windows
- Java 21+
- `llama-server.exe` from llama.cpp, or another configured local backend
- a local GGUF chat model for the managed llama.cpp path
- an embeddings model when vector retrieval is needed

The default configuration uses the engine transport with `llama_cpp` as the
default backend. Configure the llama.cpp paths in `~/.talos/config.yaml`:

```yaml
engines:
  llama_cpp:
    mode: "managed"
    server_path: "C:/path/to/llama-server.exe"
    model_path: "C:/path/to/model.gguf"
```

Ollama can still be selected explicitly as a legacy backend when needed.

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

### 6. Ask workspace questions or request approved changes

```text
What does this project do?
Read README.md and explain the architecture.
Create notes/summary.md with a short project summary.
Change only the page title in index.html.
Run the approved Gradle test command profile.
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

### Useful REPL Commands

| Command | Purpose |
|---|---|
| `/help` | show commands |
| `/mode <mode>` | switch active mode |
| `/models` | list available models |
| `/set model <backend/model>` | switch active model |
| `/reindex` | rebuild the current workspace index |
| `/workspace` | show current workspace status |
| `/status` | show runtime and indexing details |
| `/tools` | show the registered tool set |
| `/session info` | inspect current session state |
| `/clear` | clear conversation memory |
| `/q` | exit |

## The Talos Work Cycle

Talos has a structured development and review cycle:

- fast local implementation loop
- normal Gradle verification
- focused milestone audits when runtime or model behavior changes
- larger full E2E audits before important release decisions

```text
    change code
         |
         v
    .----------------------.
    | versioned candidate  |
    '----------+-----------'
               |
               v
    build -> test -> e2e -> audit -> review
               ^                         |
               |                         |
               '---- change code if needed
```

The work-cycle documentation lives here:

- [work-cycle-docs/work-test-cycle.md](work-cycle-docs/work-test-cycle.md)
- [work-cycle-docs/work-test-cycle-setup.md](work-cycle-docs/work-test-cycle-setup.md)
- [work-cycle-docs/work-test-cycle-step-by-step.md](work-cycle-docs/work-test-cycle-step-by-step.md)
- [work-cycle-docs/milestone-audit-workflow.md](work-cycle-docs/milestone-audit-workflow.md)
- [work-cycle-docs/full-e2e-audit-workflow.md](work-cycle-docs/full-e2e-audit-workflow.md)

Post-0.9.6 architecture direction is documented in
[docs/architecture/01-execution-discipline-and-local-trust.md](docs/architecture/01-execution-discipline-and-local-trust.md).

## Running Talos Well

### Hardware

Talos can run on modest hardware. Larger local models need more RAM and more
time.

Practical guidance:

- small local models are comfortable on typical developer machines
- larger local models benefit from more RAM and faster CPUs/GPUs
- SSD storage is strongly recommended for smoother indexing and model work

### Software

Current practical setup:

- Windows as the best-supported day-to-day path in this repo
- Java 21+
- managed llama.cpp for the primary local model path
- Ollama as an optional legacy backend

### Network Expectations

Talos is local-first:

- workspace data is intended to stay local
- local model backends are expected to run on the same machine or localhost
- models must be downloaded or configured ahead of use

## Quality Reports

Talos can generate reviewer-friendly Markdown quality reports from the
machine-readable summaries in `build/reports/talos/`.

Use this command for local snapshots of coverage, E2E, Qodana, and build
artifact provenance:

```powershell
./gradlew.bat writeQualityMarkdownReports
```

For a full fresh local quality run that refreshes native Qodana first:

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

The generated `reports/` folder is intentionally ignored by Git. The tracked
`reports-disabled/README.md` explains how to use it. Gradle also creates
`reports/` automatically when the report task runs.

Before writing new reports, the generator removes older generated report
snapshots with the standard report filename pattern. Manual files with other
names are preserved.

## Beta Scope

Talos is useful today for local workspace understanding, guarded file operations,
and evidence-oriented developer workflows. The beta line is still being hardened
around model reliability, command profiles, semantic verification, binary file
support, and broader capability growth.

The strongest current path is Windows plus managed llama.cpp with explicit local
model configuration. File and workspace operations are gated and traceable.
Command execution is bounded to approved profiles. Unsupported or unverified
results are reported as such.

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

The `local/` folder is for personal workspace material on this machine,
including manual-testing notes. It is intentionally ignored by Git. Generated
`reports/` are also ignored; usage instructions are kept in `reports-disabled/`.

## Summary

Talos is a local-first workspace assistant and execution harness. It combines
retrieval, local tools, approval-gated file operations, bounded command
profiles, local traces, context handling, and verification-oriented outcomes for
developer workspaces.
