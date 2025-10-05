# LOQ-J — Local-Only Java CLI for RAG

**Version:** `v0.9.0-beta`  
**Last verified commit:** `ec2f6e9`

Fast, private, citation-backed answers grounded in your current directory. LOQ-J is a local-first RAG (Retrieval-Augmented Generation) CLI that indexes your project files and enables intelligent questioning without sending data to external services.

## Why Local-First?

- **Privacy**: Your code never leaves your machine
- **Speed**: No network latency for indexing or retrieval
- **Security**: No telemetry, no external API calls, full air-gap capability
- **Control**: Customize indexing rules, embedding models, and retrieval parameters
- **Offline**: Works completely disconnected from the internet

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
loqj --version
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

- **Installation Directory**: `%LOCALAPPDATA%\Programs\loqj\`
- **User Data**: `%USERPROFILE%\.loqj\` (indices, cache, logs, config overrides)
- **PATH Entry**: Adds `%LOCALAPPDATA%\Programs\loqj\bin` to user PATH
- **No Admin Rights**: User-level installation only

---

## Quick Start

```powershell
# Navigate to your project directory
cd C:\path\to\your\project
```

```powershell
# Start interactive mode (shows banner and workspace info)
loqj
```

**In the REPL:**
```
:reindex          # Build Lucene index for current directory
What does this project do?    # Ask questions about your code
:mode rag         # Switch to RAG mode (project-aware)
:k 10             # Set retrieval top-K to 10
:debug on         # Show retrieved chunks
:q                # Quit
```

**Non-interactive usage:**
```powershell
# Index current directory
loqj rag-index
```

```powershell
# Ask questions directly
loqj rag-ask "How does authentication work?"
```

```powershell
# Check workspace status
loqj status
```

```powershell
loqj status --verbose
```

```powershell
# Work with different directories
loqj rag-index --root C:\other\project
```

```powershell
loqj rag-ask --root C:\other\project "What are the main components?"
```

---

## Commands & Modes

### CLI Commands

| Command | Purpose | Key Options | Example |
|---------|---------|-------------|---------|
| `loqj` | Interactive REPL (default) | `--no-logo`, `--root`, `--k`, `--bm25-only` | `loqj --root C:\myproject` |
| `loqj run` | Interactive REPL (explicit) | `--no-logo`, `--root`, `--k`, `--bm25-only` | `loqj run --no-logo` |
| `loqj rag-index` | Index repository files | `--root`, `--full`, `--json`, `--stats` | `loqj rag-index --full` |
| `loqj rag-ask` | Ask with RAG retrieval | `--root`, `--k` + `<question>` | `loqj rag-ask --k 5 "How does login work?"` |
| `loqj status` | Show workspace status | `--root`, `--verbose` | `loqj status --verbose` |
| `loqj version` | Version information | None | `loqj version` |
| `loqj setup` | First-run configuration | Various setup options | `loqj setup` |
| `loqj net` | Network configuration | Network-related options | `loqj net` |

### Interactive REPL Commands

| Command | Purpose | Example | Notes |
|---------|---------|---------|-------|
| `:help` | Show available commands | `:help` | Lists all REPL commands |
| `:mode <mode>` | Switch active mode | `:mode rag` | Modes: ask, rag, rag+memory, dev, web, auto |
| `:k <number>` | Set retrieval top-K | `:k 10` | Range: 1-100, affects context size |
| `:debug on\|off` | Toggle debug output | `:debug on` | Shows retrieved chunks and scores |
| `:models` | List available models | `:models` | Shows Ollama models |
| `:set model <name>` | Switch LLM model | `:set model qwen2.5:7b` | Must be pulled in Ollama first |
| `:reindex` | Rebuild current index | `:reindex` | Forces full reindex of workspace |
| `:status` | Show workspace info | `:status --verbose` | Configuration and index stats |
| `:memory clear` | Clear conversation | `:memory clear` | Resets context in memory modes |
| `:q` | Quit | `:q` | Exit REPL |

### Available Modes

| Mode | Purpose | When to Use |
|------|---------|-------------|
| `ask` | General Q&A (no indexing) | General questions, no project context needed |
| `rag` | Project-aware retrieval | Questions about your indexed codebase |
| `rag+memory` | RAG with conversation history | Multi-turn conversations about code |
| `dev` | Development-focused prompts | Code review, debugging, architecture questions |
| `web` | Web-search augmented | External information lookup (requires net.enabled) |
| `auto` | Smart mode selection | Let LOQ-J choose the best mode for your question |

---

## Embeddings: bge-m3

LOQ-J uses **`bge-m3`** via Ollama for high-quality multilingual embeddings:

```powershell
# Pull the embeddings model
ollama pull bge-m3
```

```powershell
# Verify it's available
ollama list
```

**Configuration** (in `%USERPROFILE%\.loqj\config.yaml` or default):
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
loqj run --bm25-only
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
loqj rag-ask --k 10 "How does auth work?"
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

**RAG mode (`:mode rag`):**
```
# Good prompts - specific and context-aware
How does the authentication system work in this codebase?
What are the main REST endpoints defined here?
Show me how error handling is implemented.

# Less effective - too generic
What is this project about?
Help me code.
```

**Ask mode (`:mode ask`):**
```
# Good prompts - general programming questions
What's the difference between REST and GraphQL?
How do I handle exceptions in Java?
Explain microservices architecture.
```

**Dev mode (`:mode dev`):**
```
# Good prompts - development-focused
Review this authentication flow for security issues.
What architectural improvements would you suggest?
How can I optimize this database query?
```

### Performance Tips

**Hardware optimization:**
- **SSD storage** for index files (`%USERPROFILE%\.loqj\indices\`)
- **Java 21+** for Vector API performance
- **ZGC garbage collector** (default in LOQ-J)
- **Ollama on same machine** (avoid network latency)

**Initial setup:**
```powershell
# First index takes longest (full parsing + embeddings)
loqj rag-index --full
```

```powershell
# Subsequent reindexes are incremental (file hash checking)
loqj rag-index
```

**Reindex cadence:**
- **Active development**: After major file changes
- **Stable projects**: Weekly or as-needed
- **Large codebases**: Consider splitting into focused workspaces

---

## Multi-Workspace Support

LOQ-J maintains separate indices for each workspace directory:

```powershell
# Work with web project
loqj rag-index --root C:\projects\webapp
```

```powershell
loqj rag-ask --root C:\projects\webapp "What APIs are exposed?"
```

```powershell
# Switch to mobile project (completely separate context)
loqj rag-index --root C:\projects\mobile-app
```

```powershell
loqj rag-ask --root C:\projects\mobile-app "How is data stored locally?"
```

**Environment variable shortcut:**
```powershell
# Set default workspace (avoids typing --root every time)
$env:LOQJ_WORKSPACE = "C:\projects\webapp"
```

```powershell
loqj status          # Now uses webapp by default
```

```powershell
loqj rag-ask "question"
```

**Index storage locations:**
- `%USERPROFILE%\.loqj\indices\<workspace-hash>\`
- Each workspace gets isolated Lucene index
- No cross-contamination between projects

---

## Configuration

Configuration precedence (highest to lowest):
1. **Command-line flags** (`--root`, `--k`, etc.)
2. **Environment variables** (`LOQJ_WORKSPACE`, `LOQJ_OLLAMA_HOST`)  
3. **User config** (`%USERPROFILE%\.loqj\config.yaml`)
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
  top_k_max: 100              # Maximum K value
  response_max_chars: 10485760  # 10MB response limit
  file_bytes_max: 20000       # Max file size to index
  file_lines_max: 500         # Max lines per file
  dir_entries_max: 1000       # Max files per directory
  llm_timeout_ms: 300000      # 5 minute LLM timeout
  file_timeout_ms: 10000      # 10 second file I/O timeout
  rate_per_sec: 10            # Request rate limiting
```

### Environment Variables

```powershell
# Default workspace (avoids --root flags)
$env:LOQJ_WORKSPACE = "C:\path\to\project"
```

```powershell
# Ollama connection
$env:LOQJ_OLLAMA_HOST = "http://127.0.0.1:11434"
```

```powershell
$env:LOQJ_OLLAMA_MODEL = "qwen2.5:7b"
```

```powershell
# Then just run:
loqj status
```

```powershell
loqj rag-ask "What does this project do?"
```

---

## Troubleshooting

### Installation Issues

**"Command not found" after installation:**
```powershell
# Open new terminal window (PATH changes require refresh)
# Check if PATH was updated:
$env:PATH -split ';' | Where-Object { $_ -like '*loqj*' }
```

```powershell
# If missing, reinstall:
pwsh tools\uninstall-windows.ps1
```

```powershell
pwsh tools\install-windows.ps1
```

**"loqj is not recognized" in scripts:**
```powershell
# In PowerShell scripts, use full path or refresh PATH:
& "$env:LOCALAPPDATA\Programs\loqj\bin\loqj.bat" --version
```

### Ollama Connection Issues

```powershell
# Check if Ollama is running
curl http://127.0.0.1:11434/api/version
```

```powershell
# Test with LOQ-J
loqj status --verbose
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
loqj status --verbose
```

```powershell
# Check include/exclude patterns
loqj rag-index --stats
```

```powershell
# Force complete reindex
loqj rag-index --full
```

```powershell
# Use faster BM25-only mode
loqj run --bm25-only
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
loqj status --verbose
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
loqj diagnose --mode rag --q "Summarize this project" --k 12 --print-stats
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
   loqj rag-ask --k 5 "Your question"
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
   - Configure in `%USERPROFILE%\.loqj\config.yaml`:
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
   loqj rag-index --full  # Reindex with embeddings
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

---

## Configuration

LOQ-J uses a layered configuration system with clear precedence:

**Precedence (highest to lowest):**
1. **CLI flags** (e.g., `--k 10`)
2. **Environment variables** (e.g., `LOQJ__rag__top_k=10`)
3. **User config file** (`%USERPROFILE%\.loqj\config.yaml`)
4. **Default config** (classpath: `src/main/resources/config/default-config.yaml`)

### User Configuration File

Create or edit `%USERPROFILE%\.loqj\config.yaml` to override defaults:

```yaml
# Example user config.yaml
rag:
  top_k: 8                    # Override default retrieval count
  vectors:
    enabled: true             # Enable vector search

ollama:
  host: "http://127.0.0.1:11434"
  model: "qwen2.5:7b"         # Use different model
  embed: "bge-m3"

limits:
  llm_context_max_tokens: 16384   # Override token budget
  response_max_chars: 20000000    # 20MB response limit
  llm_timeout_ms: 600000          # 10 minute timeout
```

**Note:** User config uses `.yaml` extension (not `.yml`).

### Environment Variable Overrides

Set environment variables to override config without editing files:

**Convention:** `LOQJ__section__key=value` maps to `section.key: value`

**Examples:**
```powershell
# Windows PowerShell
$env:LOQJ__rag__top_k = "10"
$env:LOQJ__limits__llm_context_max_tokens = "16384"
$env:LOQJ__ollama__model = "llama3.2:3b"

loqj rag-ask "Your question"
```

```cmd
REM Windows Command Prompt
set LOQJ__rag__top_k=10
set LOQJ__limits__response_max_chars=20000000

loqj rag-ask "Your question"
```

**Supported types:**
- Numbers: `LOQJ__rag__top_k=10` → `10` (integer)
- Booleans: `LOQJ__rag__vectors__enabled=true` → `true`
- Strings: `LOQJ__ollama__model=qwen3:8b` → `"qwen3:8b"`

### Configuration Reference

**Key settings in `limits` block:**
```yaml
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

**Check active configuration:**
```powershell
loqj diagnose --mode rag --q "test" --print-stats
```

This shows:
- Default config source
- User config path (if exists)
- Number of ENV overrides applied

---

## Multi-Workspace Support

LOQ-J maintains separate indices for each workspace directory:

```powershell
# Work with web project
loqj rag-index --root C:\projects\webapp
```
