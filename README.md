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

### File Capability And Privacy Boundaries

Talos is currently best suited for developer and text-oriented local
workspaces:

- code projects
- Markdown and plain-text notes
- JSON, YAML, XML, TOML, INI, properties, and config files
- CSV and TSV files
- static websites and source assets
- non-sensitive workspace folders where local indexing/search is acceptable

Talos can inspect and edit supported text-oriented files such as `.md`,
`.markdown`, `.txt`, `.json`, `.yaml`, `.yml`, `.csv`, `.tsv`, `.html`, `.htm`,
`.css`, `.js`, `.ts`, `.java`, `.kt`, `.kts`, `.py`, `.go`, `.rs`, `.c`,
`.cpp`, `.h`, `.hpp`, `.xml`, `.toml`, `.ini`, `.properties`, `.conf`,
`.config`, shell scripts, PowerShell scripts, Gradle files, Dockerfiles,
README files, LICENSE files, and similar project text files.

#### Capability Matrix

| Area | Beta claim | Boundary |
|---|---|---|
| Developer/text workspaces | Inspect, edit, diff, approve, checkpoint, and verify supported text files | Not arbitrary shell/browser/cloud automation |
| PDF | Text extraction for text-bearing PDFs | Not PDF creation, scanned-PDF OCR, visual layout review, or guaranteed reading order |
| Word | Text extraction for `.docx` | Not `.doc`, comments/tracked-changes fidelity, embedded objects, or valid Word document generation |
| Excel | Visible-cell extraction for `.xls`/`.xlsx` | No formula recalculation, macro execution, hidden-sheet guarantees, chart interpretation, or valid workbook generation |
| Static web | HTML/CSS/JS source editing and static coherence checks | Not browser rendering proof unless a separate browser audit is run |
| Image/OCR | Frozen out of beta product claims | Experimental OCR plumbing is not beta readiness evidence |
| PowerPoint | Frozen out of beta product claims | No PPT/PPTX reader, writer, or slide-layout understanding claim |
| Private paperwork | Not an approved beta product claim | Do not position Talos as safe for tax, health, legal, family, or admin folders until all privacy release gates pass |

Talos cannot create valid PDF/DOCX/XLS/XLSX files with the current local
text-file tool surface. It may create supported text source artifacts such as
Markdown, plain text, HTML, CSV, or JSON that a dedicated document tool can
convert later.

Talos now has narrow local extraction for text-bearing PDFs, `.docx` Word
documents, and `.xls`/`.xlsx` Excel workbooks. These are text extraction paths,
not layout-perfect document review. PDF visual order, scanned/image-only PDFs,
DOCX layout/comments/tracked changes/embedded objects, hidden workbook sheets,
charts, macros, and workbook formula recalculation remain limited and must be
reported as extraction limitations. Workbook formula cells are shown as formula
text plus cached display value when available; Talos does not recalculate them.
Large extracted output is capped and reported as partial/truncated rather than
treated as complete. Scanned or image-only PDFs are reported as requiring OCR
rather than treated as successfully reviewed. Encrypted and corrupt documents
are reported as unreadable evidence, not summarized from guesswork.

Images are frozen out of the beta scope and tracked for v1. The current code has
an experimental OCR command adapter, but beta product claims must not depend on
it. Talos must not describe image contents from filenames or guesswork. Use
`/status --verbose` to see document-extraction preflight, including whether
Image OCR is disabled, unavailable, or backed by a resolved local OCR command.
This preflight checks configuration and command resolution; it does not execute
the OCR command just to render status.

PowerPoint (`.ppt`, `.pptx`) is also frozen out of beta and tracked for v1.
Legacy Word `.doc`, archives, executables, and most binary files remain
unsupported or deferred. If one of those files exists,
Talos may identify that the file exists, but it must not claim it reviewed the
body unless a local extractor actually produced text evidence. Convert
unsupported documents to text, Markdown, HTML, CSV, or another supported text
format before relying on Talos to inspect their contents.

Sensitive personal paperwork is not an approved product claim yet. Do not
position this beta as safe for tax folders, health records, legal paperwork,
family/admin documents, or other private document folders until the privacy,
artifact-redaction, RAG-safety, unsupported-format, and private-folder-mode
release gates all pass.

Talos may create local artifacts such as model context, provider-body captures,
prompt-debug files, local turn traces, session logs, and RAG indexes.

Indirect read results are treated as a privacy boundary. `grep`, slash `/grep`,
`retrieve`, and RAG snippets are sanitized or omitted before they are handed
back to the model. Protected and unsupported files are excluded from new RAG
indexes by default, and stale index metadata is used to force rebuilds when the
privacy/file-capability policy changes.

Approved direct protected reads are different. In developer/default mode, an
approved `talos.read_file(".env")` or `talos.read_file("secrets/...")` may place
protected file contents into model context for that turn. In private mode,
approved protected reads default to `LOCAL_DISPLAY_ONLY`: the runtime reads the
file locally after approval, but withholds raw contents from model context and
redacts persisted artifacts unless an explicit `SEND_TO_MODEL_CONTEXT` scope is
enabled. This is still not enough to position Talos as safe for sensitive
paperwork folders; private-document positioning still needs stronger real-world
fixture coverage and private-folder release evidence.

Private mode is user-visible in the REPL:

- `/privacy status` shows the current privacy mode, protected-read handoff
  scope, RAG/retrieve behavior in private mode, and raw artifact persistence
  setting. It also states whether the command is changing only the current
  session/config state.
- `/privacy private on` switches the current session/config state to private
  mode.
- `/privacy private off` restores developer/default behavior after an explicit
  user command.
- `/privacy help` explains model-context and artifact boundaries.

`/privacy` does not write persistent defaults to `~/.talos/config.yaml`. Edit
`~/.talos/config.yaml` when a machine or workspace should start in private mode
by default.

After a live audit, maintainers can scan runtime artifacts with:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<audit-id>,local/manual-workspaces/<audit-id>" --no-daemon
```

The normal CI-style broad scan and the targeted live-audit scan are different:
the targeted scan is the one intended for prompt-debug, provider-body, session,
trace, turn JSONL, command-output, and generated audit-report directories.
`checkRuntimeArtifactCanaries` intentionally requires explicit
`-PartifactScanRoots=...`; it does not default to all historical manual-audit
folders because older ignored audits can contain stale canaries by design.

The document-capability live audit script can run a beta-core audit that
excludes frozen image/PPT prompts and includes private-mode PDF/DOCX/XLSX
provenance prompts with ordinary private-document fact fixtures:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -StopStaleServers
```

For the broader private-folder scripted bank, add `-PrivateFolderBank`. This
adds `/show` local-display checks, private-mode retrieve/reindex checks, a
protected-read denial probe, and a generated manual runbook for approval-sensitive
cases that still require interactive capture:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -BetaCoreOnly -PrivateFolderBank -StopStaleServers
```

It can also run the frozen image/OCR path separately when that work resumes:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -StopStaleServers
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-capability-live-audit.ps1 -UseRealOcr -StopStaleServers
```

The default non-beta-core mode uses a controlled OCR stub and proves tool
routing, privacy boundaries, and artifact handling. `-UseRealOcr` requires a
real local OCR command such as Tesseract, or `-OcrCommand <path>`, and is the
only mode that counts as production image-OCR evidence. Neither image OCR nor
PowerPoint counts as beta readiness evidence while those formats are frozen for
v1.

Talos may warn when a workspace name or shallow metadata looks sensitive, such
as tax, health, legal, finance, secrets, protected folders, or many private
document formats. This warning does not prove the folder is safe, and Talos does
not inspect protected file contents to decide whether to show it.

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
| `delete_path` | delete a file or directory inside the workspace | required |
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

### Public beta install target

The first public beta install target is Windows x64 only:

```powershell
winget install --id TalosProject.TalosCLI -e
talos setup models
talos status --verbose
talos
```

This public path is not live until a signed GitHub Release asset and winget
manifest are published. The winget package name and moniker should be
`talos-cli`, with `TalosProject.TalosCLI` as the exact package ID and
`Vissarion Zounarakis` as publisher. The public installer will include a
bundled Java runtime, so public users should not need to install Java manually. It
installs Talos only; it does not bundle a llama.cpp server or model weights.
Model setup remains an explicit post-install command through
`talos setup models`.

Until the public release exists, use the source/developer path below.
`tools/install-unix.sh is source/developer-only` and is not a supported
Linux/macOS public beta installer.

### 1. Install source/developer prerequisites

Current practical setup:

- Windows
- Java 21+
- `llama-server.exe` from llama.cpp, or another configured local backend
- a configured managed llama.cpp model profile or a local GGUF chat model
- an embeddings model when vector retrieval is needed

The default product path uses the engine transport with `llama_cpp` as the
backend. The recommended setup command configures one of the audited managed
llama.cpp model profiles:

```powershell
talos setup models
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

Those profile commands configure Hugging Face model sources and set the managed
llama.cpp process to use `~/.talos/models/huggingface` as `HF_HOME`, so model
files are downloaded under the Talos home folder on first model start.

Users who already keep GGUF files elsewhere can point Talos at that file:

```powershell
talos setup models --profile my-agent --server-path C:/path/to/llama-server.exe --model-path D:/models/agent.gguf --write
```

Existing configs can be replaced with `--force`; Talos writes a backup first.
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
| `talos doctor` | verify the local environment (config, engine, model files, server, index) |
| `talos version` | print version information |
| `talos setup` | first-run setup flow |
| `talos setup models` | configure tested managed llama.cpp model profiles |

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
| `/checkpoint` | manage local mutation checkpoints |
| `/undo` | undo the last file write/edit |
| `/privacy status` | show privacy mode, protected-read scope, RAG/retrieve, and artifact persistence |
| `/privacy private on` | enable stricter private-mode defaults for this current session/config state |
| `/privacy private off` | restore developer/default privacy behavior explicitly |
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
- [docs/setup-managed-models.md](docs/setup-managed-models.md)

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
- `talos setup models` for tested Qwen and GPT-OSS profiles
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
