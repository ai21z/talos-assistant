# Talos — Local-Only Java CLI for RAG

**Version:** `v0.9.0-beta`  
**Last verified commit:** `ec2f6e9`

Fast, private, citation-backed answers grounded in your current directory. Talos is a local-first RAG (Retrieval-Augmented Generation) CLI that indexes your project files and enables intelligent questioning without sending data to external services.

---

## Table of Contents

- [Why Talos?](#why-Talos)
- [Prerequisites (Windows)](#prerequisites-windows)
- [Installation (Windows)](#installation-windows)
- [Quick Start](#quick-start)
- [Commands & Modes](#commands--modes)
  - [CLI Commands](#cli-commands)
  - [Interactive REPL Commands](#interactive-repl-commands)
  - [Available Modes](#available-modes)
- [Embeddings: bge-m3](#embeddings-bge-m3)
- [Understanding K (Top-K)](#understanding-k-top-k)
- [Best Practices](#best-practices)
- [Per-Workspace Indexing](#per-workspace-indexing)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [Citations-Only or Empty Answers](#citations-only-or-empty-answers)

---

## Why Talos?

- **Privacy**: Your code never leaves your machine
- **Speed**: No network latency for indexing or retrieval
- **Security**: No telemetry, no external API calls, localhost-only operation
- **Per-Workspace Indexing**: Each project gets its own isolated search index
- **Control**: Customize indexing rules, embedding models, and retrieval parameters
- **Offline**: Works completely disconnected from the internet

**Note on "Air-Gap" Operation:**
Talos requires no external internet connectivity once models are downloaded. All processing happens locally via Ollama (which uses localhost HTTP communication). This is "air-gapped" in the sense that no data leaves your machine, though the localhost network stack is used for inter-process communication.

---

## Prerequisites (Windows)

- **Java 21+** (for Vector API support in Lucene)
- **Gradle** (wrapper included: `gradlew.bat`)
- **Ollama** running locally with models:
  ```powershell
  # Install chat model (default: qwen3:8b)
  ollama pull qwen3:8b
  
  # Install embeddings model (required for vector search)
  ollama pull bge-m3
  ```
- **4GB+ RAM** recommended for indexing medium-sized codebases

---

## Installation (Windows)

### First-Time Install

```powershell
# 1. Build the distribution
.\gradlew clean installDist
```

```powershell
# 2. Install to user PATH (no admin required)
pwsh tools\install-windows.ps1
```

```powershell
# 3. Open new terminal window and verify
talos --version
```

### After Making Changes

```powershell
# 1. Clean and rebuild
.\gradlew clean installDist
```

```powershell
# 2. Uninstall previous version
pwsh tools\uninstall-windows.ps1
```

```powershell
# 3. Reinstall
pwsh tools\install-windows.ps1
```

### What Installation Creates

- **Installation Directory**: `%LOCALAPPDATA%\Programs\talos\`
- **User Data**: `%USERPROFILE%\.talos\` (indices, cache, logs, config overrides)
- **PATH Entry**: Adds `%LOCALAPPDATA%\Programs\talos\bin` to user PATH
- **No Admin Rights**: User-level installation only

---

## Quick Start

```powershell
# Navigate to your project directory
cd C:\path\to\your\project
```

```powershell
# Start interactive mode (shows banner and workspace info)
talos
```

**In the REPL:**
```
/reindex          # Build Lucene index for current directory
What does this project do?    # Ask questions about your code
/mode rag         # Switch to RAG mode (project-aware)
/k 10             # Set retrieval top-K to 10
/debug on         # Show retrieved chunks
/q                # Quit
```

**Non-interactive usage:**
```powershell
# Index current directory
talos rag-index
```

```powershell
# Ask questions directly
talos rag-ask "How does authentication work?"
```

```powershell
# Check workspace status
talos status
```

```powershell
talos status --verbose
```

```powershell
# Work with different directories
talos rag-index --root C:\other\project
```

```powershell
talos rag-ask --root C:\other\project "What are the main components?"
```

---

## Commands & Modes

### CLI Commands

| Command | Purpose | Key Options | Example |
|---------|---------|-------------|---------|
| `talos` | Interactive REPL (default) | `--no-logo`, `--root`, `--k`, `--bm25-only` | `talos --root C:\myproject` |
| `talos run` | Interactive REPL (explicit) | `--no-logo`, `--root`, `--k`, `--bm25-only` | `talos run --no-logo` |
| `talos rag-index` | Index repository files | `--root`, `--full`, `--json`, `--stats` | `talos rag-index --full` |
| `talos rag-ask` | Ask with RAG retrieval | `--root`, `--k` + `<question>` | `talos rag-ask --k 5 "How does login work?"` |
| `talos status` | Show workspace status | `--root`, `--verbose` | `talos status --verbose` |
| `talos diagnose` | Diagnose RAG configuration | `--mode`, `--k`, `-q/--question`, `--print-stats` | `talos diagnose --mode rag --q "test" --print-stats` |
| `talos version` | Version information | None | `talos version` |
| `talos setup` | First-run configuration | Various setup options | `talos setup` |
| `talos net` | Network configuration | Network-related options | `talos net` |

### Interactive REPL Commands

| Command | Purpose | Example | Notes |
|---------|---------|---------|-------|
| `/help` | Show available commands | `/help` | Lists all REPL commands |
| `/files` | List directories and files | `/files` | Shows workspace directory structure and indexed files |
| `/grep <regex>` | Search for patterns in files | `/grep "TODO"` | Searches workspace files with line numbers |
| `/workspace` | Show current workspace info | `/workspace` | Displays workspace path, index location, and doc count |
| `/mode <mode>` | Switch active mode | `/mode rag` | Modes: ask, rag, dev, auto |
| `/k <number>` | Set retrieval top-K | `/k 10` | Range: 1-100, affects context size |
| `/debug on\|off` | Toggle debug output | `/debug on` | Shows retrieved chunks and scores |
| `/models` | List available models | `/models` | Shows Ollama models |
| `/set model <name>` | Switch LLM model | `/set model qwen2.5:7b` | Must be pulled in Ollama first |
| `/set <key> <value>` | Set configuration value | `/set top_k 10` | Runtime configuration changes |
| `/show <key>` | Show configuration value | `/show top_k` | Display current setting |
| `/reindex` | Rebuild current index | `/reindex` | Forces full reindex of workspace |
| `:status` | Show workspace info | `:status --verbose` | Configuration and index stats |
| `:q` | Quit | `:q` | Exit REPL |

### Available Modes

| Mode | Purpose | When to Use |
|------|---------|-------------|
| `ask` | General Q&A (no indexing) | General questions, no project context needed |
| `rag` | Project-aware retrieval | Questions about your indexed codebase |
| `dev` | Local file operations | View files and list directories (`ls`, `open`, `show`) |
| `web` | Reserved stub | Not implemented; returns a reserved-mode message only |
| `auto` | Smart mode selection | Let Talos choose the best mode for your question |

**Notes on modes:**
- `rag+memory` mode exists in code but is **deprecated and non-functional** (just redirects to `rag`)
- `web` mode is a **reserved stub** only. It is intentionally exposed, but it does not perform browser or external web actions in this build.
- For actual functionality, use `ask`, `rag`, `dev`, or `auto`

---

## Embeddings: bge-m3

Talos uses **`bge-m3`** via Ollama for high-quality multilingual embeddings:

```powershell
# Pull the embeddings model
ollama pull bge-m3
```

```powershell
# Verify it's available
ollama list
```

**Configuration** (in `%USERPROFILE%\.talos\config.yaml` or default):
```yaml
ollama:
  embed: "bge-m3"           # Embeddings model name
  host: "http://127.0.0.1:11434"  # Ollama endpoint

rag:
  vectors:
    enabled: true           # Enable vector search (disable with --bm25-only)
  embed_concurrency: 4      # Parallel embedding requests
```

**Disable vectors** (BM25-only mode for faster indexing):
```powershell
talos run --bm25-only
```

---

## Understanding K (Top-K)

The **`k`** parameter controls how many text snippets are retrieved from your index to provide context for the LLM:

### How K Works
- **Higher K** = More context, better answers, slower responses, more RAM usage
- **Lower K** = Faster responses, less context, may miss relevant information
- **Default**: `k=6` (from `src/main/resources/config/default-config.yaml`)

### Choosing K Values

| Project Size | Recommended K | Rationale |
|--------------|---------------|-----------|
| Small (< 100 files) | k=3-5 | Less context needed, avoid overwhelming LLM |
| Medium (100-1000 files) | k=6-10 | Default range, good balance |
| Large (1000+ files) | k=8-15 | More context needed to find relevant info |
| Very Large (enterprise) | k=12-20 | Maximum context for complex queries |

### Machine Considerations
- **8GB RAM**: Keep k ≤ 10
- **16GB RAM**: k ≤ 15 works well  
- **32GB+ RAM**: k ≤ 20 for large projects
- **SSD recommended** for large indices

### Configuration
```yaml
# In config file
rag:
  top_k: 6                 # Default retrieval count

limits:
  top_k_max: 100           # Maximum allowed K value
```

```powershell
# At runtime
talos rag-ask --k 10 "How does auth work?"
```
**Or in REPL:**
```
:k 10
```

---

## Best Practices

### Shaping Your Workspace

**Include the right files:**
```yaml
# Default includes (from src/main/resources/config/default-config.yaml)
rag:
  includes:
    - "**/*.md"      # Documentation
    - "**/*.java"    # Source code
    - "**/*.yml"     # Configuration
    - "**/*.json"    # Config/data files
    - "**/README*"   # Project docs
    # ... see full list in config
```

**Exclude build artifacts and binaries:**
```yaml
rag:
  excludes:
    - "**/.git/**"
    - "**/build/**"
    - "**/node_modules/**"
    - "**/*.jar"
    - "**/*.exe"
    # ... see full list in config
```

**Performance tips:**
- Keep workspace focused (avoid indexing massive repos)
- Exclude test fixtures and generated code
- Use `.gitignore` patterns as a guide
- Prefer source files over compiled artifacts

### Prompting Per Mode

**RAG mode (`/mode rag`):**
```
# Good prompts - specific and context-aware
How does the authentication system work in this codebase?
What are the main REST endpoints defined here?
Show me how error handling is implemented.

# Comparing files (both separators work)
Summarize the differences between README.md and docs\landing.md
Compare docs/landing.md with README.md

# Referencing nested files
What does src\main\java\App.java do?
Explain the config/app.yml settings

# Less effective - too generic
What is this project about?
Help me code.
```

**Path Separator Equivalence:**
- You can reference files with either `\` (Windows) or `/` (POSIX) separators
- Talos treats them identically and normalizes paths in `[Sources]` output
- Example: `docs\landing.md` and `docs/landing.md` refer to the same file
- Sources are always displayed with forward slashes for cross-platform consistency

**Ask mode (`/mode ask`):**
```
# Good prompts - general programming questions
What's the difference between REST and GraphQL?
How do I handle exceptions in Java?
Explain microservices architecture.
```

**Dev mode (`/mode dev`):**
```
# File operations
ls                    # List current directory
ls src/main          # List specific directory
open README.md       # View file contents
show config/app.yml  # View configuration file
```

### Performance Tips

**Hardware optimization:**
- **SSD storage** for index files (`%USERPROFILE%\.talos\indices\`)
- **Java 21+** for Vector API performance
- **ZGC garbage collector** (default in Talos)
- **Ollama on same machine** (avoid network latency)

**Initial setup:**
```powershell
# First index takes longest (full parsing + embeddings)
talos rag-index --full
```

```powershell
# Subsequent reindexes are incremental (file hash checking)
talos rag-index
```

**Reindex cadence:**
- **Active development**: After major file changes
- **Stable projects**: Weekly or as-needed
- **Large codebases**: Consider splitting into focused workspaces

---

## Per-Workspace Indexing

Talos creates a separate search index for each workspace directory you work with.

### How It Works

**One workspace per terminal session:**
- Each `talos` process works with **one workspace at a time**
- The workspace is determined by: `--root` flag, `TALOS_WORKSPACE` environment variable, or current directory
- Different terminal windows can work with different workspaces independently

**Isolated indices:**
- Each workspace gets its own Lucene index stored at `%USERPROFILE%\.talos\indices\<workspace-hash>\`
- The hash is computed from the absolute workspace path
- Switching workspaces means switching to a completely different index
- No mixing of results across workspaces

### Usage Examples

**Working with different projects:**

```powershell
# Terminal 1: Working with web app
cd C:\projects\webapp
talos rag-index
talos rag-ask "What APIs are exposed?"
```

```powershell
# Terminal 2: Working with mobile app (completely separate)
cd C:\projects\mobile-app
talos rag-index
talos rag-ask "How is data stored locally?"
```

```powershell
# Terminal 3: Working with desktop app (another separate workspace)
cd C:\projects\desktop-app
talos rag-index
talos rag-ask "What frameworks are used?"
```

**Switching workspaces in the same terminal:**

```powershell
# Index first project
talos rag-index --root C:\projects\webapp
talos rag-ask --root C:\projects\webapp "What APIs are exposed?"
```

```powershell
# Switch to second project
talos rag-index --root C:\projects\mobile-app
talos rag-ask --root C:\projects\mobile-app "How is data stored locally?"
```

```powershell
# Switch to third project
talos rag-index --root C:\projects\desktop-app
talos rag-ask --root C:\projects\desktop-app "What frameworks are used?"
```

**Using environment variable for default workspace:**

```powershell
# Set default workspace (avoids typing --root every time)
$env:TALOS_WORKSPACE = "C:\projects\webapp"
```

```powershell
talos status          # Now uses webapp by default
talos rag-ask "question"
```

### Index Management

**Index storage:**
- Location: `%USERPROFILE%\.talos\indices\<workspace-hash>\`
- Each workspace gets its own subdirectory based on a hash of its path
- Indices persist across talos sessions

**Cleaning indices:**
- **No built-in index cleanup command** - indices are kept indefinitely
- Manual cleanup: Delete `%USERPROFILE%\.talos\indices\` directory or specific workspace subdirectories
- Uninstall with cleanup: `pwsh tools\uninstall-windows.ps1 -Purge` removes all indices

**Index isolation guarantees:**
- No cross-contamination between projects
- Each workspace can have different include/exclude patterns
- Switching workspaces is instant (just changes which index to query)

---

## Configuration

Configuration precedence (highest to lowest):
1. **Command-line flags** (`--root`, `--k`, etc.)
2. **Environment variables** (`TALOS_WORKSPACE`, `TALOS_OLLAMA_HOST`)  
3. **User config** (`%USERPROFILE%\.talos\config.yaml`)
4. **Default config** (`src/main/resources/config/default-config.yaml`)

### Key Configuration Values

```yaml
# RAG settings
rag:
  top_k: 6                    # Default retrieval count
  chunk_chars: 1200           # Text chunk size
  chunk_overlap: 150          # Chunk overlap for context
  embed_concurrency: 4        # Parallel embedding requests
  force_full_reindex: false   # Ignore file hashes
  vectors:
    enabled: true             # Vector search (disable with --bm25-only)

# LLM settings  
ollama:
  host: "http://127.0.0.1:11434"
  model: "qwen3:8b"           # Default chat model
  embed: "bge-m3"             # Embeddings model
  allow_remote: false         # Security: localhost only

# Network policy
net:
  enabled: true               # Allow network for web mode, model downloads

# Performance limits
limits:
  top_k_max: 100                      # Maximum allowed K value
  response_max_chars: 10485760        # 10MB response cap
  llm_context_max_tokens: 8192        # Token budget for prompt validation
  llm_timeout_ms: 300000              # 5 minutes
  file_bytes_max: 20000               # Skip files larger than this
  file_lines_max: 500                 # Skip files with more lines
  dir_entries_max: 1000               # Max files per directory
  dir_depth_max: 10                   # Max directory nesting
```

### Environment Variables

```powershell
# Default workspace (avoids --root flags)
$env:TALOS_WORKSPACE = "C:\path\to\project"
```

```powershell
# Ollama connection
$env:TALOS_OLLAMA_HOST = "http://127.0.0.1:11434"
```

```powershell
$env:TALOS_OLLAMA_MODEL = "qwen2.5:7b"
```

```powershell
# Then just run:
talos status
```

```powershell
talos rag-ask "What does this project do?"
```

---

## Troubleshooting

### Installation Issues

**"Command not found" after installation:**
```powershell
# Open new terminal window (PATH changes require refresh)
# Check if PATH was updated:
$env:PATH -split ';' | Where-Object { $_ -like '*talos*' }
```

```powershell
# If missing, reinstall:
pwsh tools\uninstall-windows.ps1
```

```powershell
pwsh tools\install-windows.ps1
```

**"talos is not recognized" in scripts:**
```powershell
# In PowerShell scripts, use full path or refresh PATH:
& "$env:LOCALAPPDATA\Programs\talos\bin\talos.bat" --version
```

### Ollama Connection Issues

```powershell
# Check if Ollama is running
curl http://127.0.0.1:11434/api/version
```

```powershell
# Test with Talos
talos status --verbose
```

```powershell
# If connection fails, check Ollama service:
ollama serve    # Start Ollama if not running
```

```powershell
ollama list     # Verify models are available
```

### Indexing Problems

**Empty or slow indices:**
```powershell
# See what files were found
talos status --verbose
```

```powershell
# Check include/exclude patterns
talos rag-index --stats
```

```powershell
# Force complete reindex
talos rag-index --full
```

```powershell
# Use faster BM25-only mode
talos run --bm25-only
```

**"No embeddings model" errors:**
```powershell
# Ensure bge-m3 is pulled
ollama pull bge-m3
```

```powershell
ollama list | findstr bge-m3
```

```powershell
# Check configuration
talos status --verbose
```

### Performance Issues

**High memory usage:**
- Reduce `k` parameter: `:k 5`
- Use `--bm25-only` flag to disable vectors
- Exclude large files from indexing
- Consider smaller workspace scope

**Slow responses:**
- Check available RAM during queries
- Verify SSD storage for index files
- Reduce `embed_concurrency` in config
- Use local Ollama (not remote)

---

## Citations-Only or Empty Answers

If you see citations but no answer text (or "citations-only" output), this usually means the context exceeded the model's token budget or the model failed to generate a response.

**Symptoms:**
- Citations appear at the bottom
- Answer body is missing or empty
- WARN messages like `RAG_CONTEXT_TRIMMED` or `RAG_GEN_EMPTY`

**Quick Diagnosis:**
```powershell
# Run diagnostics to check prompt size and model capacity
talos diagnose --mode rag --q "Summarize this project" --k 12 --print-stats
```

The diagnose command shows:
- Configuration sources (default, user, ENV)
- Ollama connection status
- Token budget and utilization
- Whether context was trimmed
- Whether the answer body is empty

**Common Causes & Fixes:**

1. **Context window exceeded (K too high)**
   ```powershell
   # Reduce top-K retrieval count
   talos rag-ask --k 5 "Your question"
   # Or in REPL:
   :k 5
   ```

2. **Model not running**
   ```powershell
   # Check Ollama service
   ollama list
   ollama ps
   ```

3. **Model context limit reached**
   - Default fallback: 8192 tokens
   - Configure in `%USERPROFILE%\.talos\config.yaml`:
   ```yaml
   limits:
     llm_context_max_tokens: 16384  # If your model supports more
   ```

4. **Large files in snippets**
   - Enable vectors for better relevance ranking:
   ```yaml
   rag:
     vectors:
       enabled: true
   ```
   ```powershell
   talos rag-index --full  # Reindex with embeddings
   ```

5. **Network/transport disabled**
   - Check config:
   ```yaml
   net:
     enabled: true
   llm:
     transport: "engine"  # Not "placeholder"
   ```

**Expected Behavior:**
- Answer text appears **first**
- Citations appear **second** (at the bottom)
- If context is trimmed, you'll see a WARN message but still get an answer
