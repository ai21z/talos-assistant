# Talos Technical Analysis (formerly LOQ-J)

**Version:** `v0.9.0-beta`  
**Last verified commit:** `ec2f6e9`

This document provides a technical deep-dive into LOQ-J's architecture, implementation details, and operational characteristics for engineers working with or extending the codebase.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Key Packages & Classes](#key-packages--classes)
- [RAG Pipeline Deep-Dive](#rag-pipeline-deep-dive)
- [Configuration Model](#configuration-model)
- [LLM Client Architecture](#llm-client-architecture)
- [First-Run & Context Directory](#first-run--context-directory)
- [Per-Workspace Indexing](#per-workspace-indexing)
- [Test Coverage & Limits](#test-coverage--limits)
- [Operational Notes](#operational-notes)

---

## Architecture Overview

LOQ-J follows a layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────┐
│ App Layer (dev.loqj.app)                │
│ ├── Main.java (Entry point)             │
│ └── ui/ (First-run wizard)              │
├─────────────────────────────────────────┤
│ CLI Layer (dev.loqj.cli)                │
│ ├── cmds/ (Picocli commands)            │
│ ├── modes/ (REPL interaction modes)     │
│ ├── repl/ (Interactive shell)           │
│ └── commands/ (REPL command registry)   │
├─────────────────────────────────────────┤
│ Core Layer (dev.loqj.core)              │
│ ├── rag/ (RAG orchestration)            │
│ ├── index/ (Lucene indexing)            │
│ ├── search/ (Query & retrieval)         │
│ ├── embed/ (Embeddings via Ollama)      │
│ ├── llm/ (Chat model client)            │
│ ├── ingest/ (File parsing & chunking)   │
│ └── Config (YAML configuration)         │
├─────────────────────────────────────────┤
│ Engine Layer (dev.loqj.engine)          │
│ ├── ollama/ (Ollama HTTP client)        │
│ └── stubs/ (Test doubles)               │
├─────────────────────────────────────────┤
│ SPI Layer (dev.loqj.spi)                │
│ ├── ModelEngine (pluggable backends)    │
│ ├── ModelCatalog (model metadata)       │
│ └── BackendProcessManager (lifecycle)   │
└─────────────────────────────────────────┘
```

### Layer Descriptions

#### App Layer (`dev.loqj.app`)
Application entry point and first-run setup.

- **`Main.java`** - Entry point; checks if first-run wizard is needed, otherwise launches Picocli command parsing
- **`ui/FirstRunWizard`** - Interactive setup wizard that creates `~/.loqj/` directory structure and validates Ollama models on first launch

#### CLI Layer (`dev.loqj.cli`)
Command-line interface and interactive REPL.

- **`cmds/`** - Picocli command implementations for batch operations
  - `RootCmd` - Main command that delegates to subcommands
  - `RunCmd` - Launches interactive REPL with JLine terminal
  - `RagIndexCmd` - Batch indexing command
  - `RagAskCmd` - One-shot RAG query command
  - `StatusCmd` - Shows workspace and configuration status
  - `SetupCmd`, `NetCmd`, `VersionCmd`, `DiagnoseCmd` - Utility commands

- **`modes/`** - REPL interaction strategies for different query types
  - `Mode` - Interface defining `canHandle()` and `handle()` methods
  - `AskMode` - Direct LLM queries without indexing
  - `RagMode` - Retrieval-augmented generation using workspace index
  - `AutoMode` - Automatic mode selection based on query heuristics
  - `DevMode`, `WebMode` - Specialized prompting strategies
  - `ModeController` - Routes user prompts to appropriate mode

- **`repl/`** - Interactive shell infrastructure
  - `ReplRouter` - Dispatches colon-commands and routes natural language prompts through modes
  - `RenderEngine` - Formats and displays results in terminal (spinner, boxes, sanitization)
  - `ExecutionPipeline` - Rate-limiting and validation for command execution
  - `SessionState` - Tracks per-session settings (k, debug mode)
  - `Context` - Provides access to RAG service, config, and workspace for commands

- **`commands/`** - REPL colon-commands (`:help`, `:files`, `:reindex`, etc.)
  - `Command` - Interface for REPL commands
  - `CommandRegistry` - Registers and dispatches commands by name
  - `FilesCommand` - Lists workspace directories and indexed files
  - `HelpCommand`, `ModelsCommand`, `StatusCommand`, `DebugCommand`, etc.

#### Core Layer (`dev.loqj.core`)
Business logic for RAG, indexing, and LLM interaction.

- **`rag/`** - RAG pipeline orchestration
  - `RagService` - Main service that coordinates retrieval and generation
  - `PromptValidator` - Validates prompts fit within token budgets
  - `MemoryManager` - Manages conversation history for RAG+memory mode

- **`index/`** - Lucene index management
  - `Indexer` - Walks workspace, parses files, generates embeddings, writes to Lucene
  - `LuceneStore` - Low-level Lucene operations (BM25 search, vector search, document storage)
  - `IndexingStats` - Tracks indexing performance metrics

- **`search/`** - Query processing and result ranking
  - `Retriever` - Implements Reciprocal Rank Fusion (RRF) to combine BM25 and vector search results
    - **RRF Formula**: `score = 1 / (k + rank)` where k=60 (hardcoded constant)
    - **Implementation**: `Retriever.fuseRrf()` called from `RagService` with fixed k=60
    - **Not configurable**: RRF constant is hardcoded, no YAML configuration option
  - `SnippetBuilder` - Assembles retrieved chunks into context snippets with deduplication
    - **Path normalization**: Converts Windows backslashes to forward slashes via `RagMode.normalizePathSeparators()`
    - **Location**: Private method in `dev.loqj.cli.modes.RagMode` (no centralized PathUtil class)

- **`embed/`** - Embeddings generation
  - `EmbeddingsClient` - HTTP client for Ollama embeddings API
  - `CachingEmbeddings` - SQLite-backed cache to avoid re-embedding identical text
  - `BatchEmbeddings` - Batches embedding requests for performance

- **`llm/`** - Chat model interaction
  - `LlmClient` - HTTP client for Ollama chat API (streaming and non-streaming)
  - `CachingLanguageModel` - Optional response cache
  - `OllamaModels` - Model catalog utilities

- **`ingest/`** - File parsing and text extraction
  - `FileWalker` - Walks workspace directory applying glob include/exclude patterns
  - `ParserUtil` - Extracts text from various file formats (plain text, HTML, PDF, Office docs)
  - `Chunker` - Splits text into overlapping chunks with sentence-boundary awareness
  - `ParsedChunk` - Data structure holding chunk text and metadata

- **`Config`** - YAML configuration loader with layered precedence (CLI flags > ENV > user config > defaults)
- **`IndexPathResolver`** - Computes workspace hash and resolves index directory path

#### Engine Layer (`dev.loqj.engine`)
Backend implementations for LLM and embeddings.

- **`ollama/`** - Ollama backend implementation
  - `OllamaEngine` - Implements `ModelEngine` SPI for Ollama HTTP API
  - `OllamaEngineProvider` - Factory for creating Ollama engine instances
  - `OllamaCatalog` - Lists available Ollama models

- **`stubs/`** - Test doubles for offline development and testing (gpt4all, llamacpp stubs)

#### SPI Layer (`dev.loqj.spi`)
Service Provider Interface for pluggable backends.

- **`ModelEngine`** - Interface for LLM backends (chat, chatStream, embed methods)
- **`ModelEngineProvider`** - Factory interface for creating engine instances
- **`ModelCatalog`** - Interface for listing available models
- **`BackendProcessManager`** - Interface for managing backend lifecycle (start/stop/health)

### Data Flow

1. **CLI Entry** → `Main.java` checks for first run → Picocli parses command → `RootCmd` routes to subcommand
2. **Interactive Mode** → `RunCmd` starts JLine REPL → `ReplRouter` processes each input line
3. **Mode Routing** → `ReplRouter` sends natural language prompts to `ModeController` → Mode's `handle()` method executes
4. **RAG Query** → `RagService.ask()` → `Retriever` searches index → `SnippetBuilder` assembles context → `LlmClient` generates answer
5. **Indexing** → `Indexer.index()` → `FileWalker` finds files → `ParserUtil` extracts text → `Chunker` splits → `EmbeddingsClient` embeds → `LuceneStore` writes
6. **Result Rendering** → Mode returns `Result` → `RenderEngine` formats (sanitize, box, spinner) → Terminal output

---

## Key Packages & Classes

### CLI Command Structure (`dev.loqj.cli.cmds`)

| Class | Purpose | Picocli Annotation | Key Methods |
|-------|---------|-------------------|-------------|
| `RootCmd` | Main command entry point | `@Command(name="loqj")` | Delegates to `RunCmd` by default |
| `RunCmd` | Interactive REPL launcher | `@Command(name="run")` | `run()` - starts JLine terminal |
| `RagIndexCmd` | Batch indexing command | `@Command(name="rag-index")` | `run()` - calls `Indexer.index()` |
| `RagAskCmd` | One-shot RAG query | `@Command(name="rag-ask")` | `run()` - calls `RagService.ask()` |
| `StatusCmd` | Workspace status checker | `@Command(name="status")` | `run()` - shows config & index stats |
| `SetupCmd` | First-run configuration | `@Command(name="setup")` | `run()` - wizard setup |
| `NetCmd` | Network configuration | `@Command(name="net")` | `run()` - network settings |
| `VersionCmd` | Version information | `@Command(name="version")` | `run()` - shows version info |

**REPL Commands** (`dev.loqj.cli.commands`):
- `FilesCommand` - Lists workspace directories and indexed files (`:files`)
- `HelpCommand` - Shows available REPL commands (`:help`)
- `ModelsCommand` - Lists available Ollama models (`:models`)
- `StatusCommand` - Shows configuration and index stats (`:status`)
- Command registration via `ReplRouter`

**FilesCommand Enhancement:**
- Extracts parent directories from indexed file paths
- Shows directories first, then files
- Handles nested directory structures (e.g., `a/b/c/file.txt` → shows `a/`, `a/b/`, `a/b/c/`)
- Normalizes path separators (Windows `\` → POSIX `/`)
- Provides deterministic workspace structure without LLM hallucination

**Command registration** in `RootCmd.subcommands`:
```java
subcommands = {
    SetupCmd.class, RagIndexCmd.class, RagAskCmd.class, RunCmd.class,
    NetCmd.class, TopLevelStatusCmd.class, VersionCmd.class
}
```

### Mode System (`dev.loqj.cli.modes`)

| Mode Class | Strategy Name | canHandle() Logic | Key Behavior |
|------------|---------------|-------------------|--------------|
| `AskMode` | "ask" | Always true (fallback) | Direct LLM queries, no indexing |
| `RagMode` | "rag" | True for most queries | Index retrieval + LLM generation |
| `RagMemoryMode` | "rag+memory" | True + conversation history | Multi-turn RAG with context |
| `DevMode` | "dev" | Code-related keywords | Development-focused prompts |
| `WebMode` | "web" | Web/search keywords | External search integration |
| `AutoMode` | "auto" | Smart heuristics | Tries dev→rag→ask in sequence |

**Mode controller logic** (`dev.loqj.cli.modes.ModeController`):
- **Single-pass routing**: Each mode's `canHandle()` called once
- **Auto mode cascade**: dev → rag → ask → full sweep
- **Active mode concept**: User can explicitly set mode via `:mode <name>`

### Core RAG Pipeline (`dev.loqj.core`)

| Package | Key Classes | Purpose |
|---------|-------------|---------|
| `rag/` | `RagService`, `RagAnswer` | Main RAG orchestration |
| `index/` | `Indexer`, `LuceneStore` | File indexing & Lucene management |
| `search/` | `SearchService`, `SnippetBuilder` | Query processing & result ranking |
| `embed/` | `EmbeddingsClient`, `BatchEmbeddings` | BGE-M3 embeddings via Ollama |
| `ingest/` | `ChunkerService`, `ParserUtil` | File parsing & text chunking |
| `llm/` | `LlmClient`, `LlmResponse` | Chat model interaction |

---

## RAG Pipeline Deep-Dive

### 1. File Discovery & Filtering

**Location**: `dev.loqj.core.index.Indexer.index()`

```java
// Glob-based filtering from config
List<String> includes = cfg.getStringList("rag.includes");
List<String> excludes = cfg.getStringList("rag.excludes");

// File traversal with size/depth limits
int maxDepth = cfg.getInt("limits.dir_depth_max", 10);
long maxBytes = cfg.getLong("limits.file_bytes_max", 20000);
```

**Default includes** (from `src/main/resources/config/default-config.yaml`):
- Source code: `**/*.java`, `**/*.kt`, `**/*.py`, `**/*.js`, etc.
- Documentation: `**/*.md`, `**/*.txt`, `**/README*`
- Configuration: `**/*.yml`, `**/*.json`, `**/*.xml`

**Default excludes**:
- Build artifacts: `**/build/**`, `**/target/**`, `**/node_modules/**`
- Version control: `**/.git/**`, `**/.idea/**`
- Binaries: `**/*.jar`, `**/*.exe`, `**/*.png`

### 2. File Parsing & Chunking

**Location**: `dev.loqj.core.ingest.ParserUtil` + `dev.loqj.core.ingest.ChunkerService`

**Supported formats**:
- **Plain text**: `.md`, `.txt`, `.java`, `.py`, etc.
- **HTML**: `.html`, `.htm` (via JSoup in `dev.loqj.core.ingest.ParserUtil`)
- **PDF**: `.pdf` (via PDFBox - see `build.gradle.kts` dependency)
- **Office docs**: `.docx`, `.xlsx` (via Apache POI)

**Chunking strategy**:
```java
// From default-config.yaml
rag:
  chunk_chars: 1200      // Target chunk size
  chunk_overlap: 150     // Overlap between chunks
```

**Implementation**: Sentence-boundary aware chunking to preserve semantic coherence.

### 3. Embeddings Generation

**Location**: `dev.loqj.core.embed.EmbeddingsClient`

**Model**: `bge-m3` via Ollama HTTP API

**Batch processing**:
```java
// From default-config.yaml  
rag:
  embed_concurrency: 4   // Parallel embedding requests
```

**Ollama integration**:
```java
// HTTP client in dev.loqj.engine.ollama.OllamaEmbeddingsClient
POST http://127.0.0.1:11434/api/embeddings
{
  "model": "bge-m3",
  "prompt": "text to embed"
}
```

### 4. Lucene Index Storage

**Location**: `dev.loqj.core.index.LuceneStore`

**Index structure**:
- **BM25 fields**: `content`, `path`, `title`
- **Vector fields**: Dense vectors from BGE-M3 (if vectors enabled)
- **Metadata**: File path, modification time, chunk boundaries

**Storage location**: `%USERPROFILE%\.loqj\indices\<workspace-hash>\`

**Lucene version**: 10.x (see `build.gradle.kts` luceneVersion property)

### 5. Query Processing & Retrieval

**Location**: `dev.loqj.core.search.SearchService`

**Hybrid search**:
1. **BM25 search** on text content (always enabled)
2. **Vector search** via Lucene HNSW (if `rag.vectors.enabled: true`)
3. **Score fusion** combining both approaches

**Top-K retrieval**:
```java
// Configurable via --k flag or config
int topK = cfg.getInt("rag.top_k", 6);
List<SearchResult> results = searchService.search(query, topK);
```

### 6. Context Assembly & LLM Generation

**Location**: `dev.loqj.core.rag.RagService.ask()`

**Prompt template** (from `src/main/resources/prompts/rag-system.txt`):
```
You are a helpful assistant with access to retrieved context...
[CONTEXT]
{retrieved_snippets}
[/CONTEXT]

User question: {question}
```

**LLM client**: `dev.loqj.core.llm.LlmClient` → Ollama HTTP API

**Streaming support**: Real-time token generation for interactive experience

---

## Configuration Model

### Configuration Hierarchy

1. **Command-line flags** (highest priority)
2. **Environment variables** (`LOQJ_*` prefix)
3. **User config** (`%USERPROFILE%\.loqj\config.yaml`)
4. **Default config** (`src/main/resources/config/default-config.yaml`)

### Key Configuration Classes

| Class | Purpose | Location |
|-------|---------|----------|
| `Config` | Main configuration loader | `dev.loqj.core.Config` |
| `CfgUtil` | YAML parsing utilities | `dev.loqj.core.CfgUtil` |

### Critical Configuration Keys

```yaml
# RAG behavior
rag:
  top_k: 6                    # Retrieved snippets count
  chunk_chars: 1200           # Text chunk target size  
  chunk_overlap: 150          # Chunk overlap
  embed_concurrency: 4        # Parallel embeddings
  force_full_reindex: false   # Bypass file hash checking
  vectors:
    enabled: true             # Enable vector search
  includes: [...]             # File inclusion patterns
  excludes: [...]             # File exclusion patterns

# LLM connection
ollama:
  host: "http://127.0.0.1:11434"
  model: "qwen3:8b"           # Default chat model
  embed: "bge-m3"             # Embeddings model
  allow_remote: false         # Security: localhost only

# Security policy  
net:
  enabled: true               # Allow network access

# Performance limits
limits:
  top_k_max: 100              # Maximum K value
  response_max_chars: 10485760  # 10MB response limit
  dir_depth_max: 10           # Directory traversal depth
  file_bytes_max: 20000       # Max file size to index
  file_lines_max: 500         # Max lines per file
  dir_entries_max: 1000       # Max files per directory
  llm_timeout_ms: 300000      # 5 minute LLM timeout
  file_timeout_ms: 10000      # 10 second file I/O timeout
  rate_per_sec: 10            # Request rate limiting
```

### Environment Variable Mapping

| Environment Variable | Config Key | Example |
|---------------------|------------|---------|
| `LOQJ_WORKSPACE` | N/A (CLI override) | `C:\projects\webapp` |
| `LOQJ_OLLAMA_HOST` | `ollama.host` | `http://127.0.0.1:11434` |
| `LOQJ_OLLAMA_MODEL` | `ollama.model` | `qwen2.5:7b` |

---

## LLM Client Architecture

### Backend Abstraction

**SPI Interface**: `dev.loqj.spi.ModelEngine`

```java
public interface ModelEngine {
    ModelEngineType getType();
    LlmResponse chat(LlmRequest request) throws Exception;
    List<Double> embed(String text) throws Exception;
    // ... other methods
}
```

### Ollama Implementation

**Primary backend**: `dev.loqj.engine.ollama.OllamaEngine`

**HTTP endpoints used**:
- `POST /api/chat` - Chat completions (streaming & non-streaming)
- `POST /api/embeddings` - Text embeddings  
- `GET /api/tags` - List available models
- `GET /api/version` - Ollama version check

**Connection management**:
```java
// From dev.loqj.engine.ollama.OllamaLlmClient
String ollamaHost = config.getString("ollama.host", "http://127.0.0.1:11434");
boolean allowRemote = config.getBoolean("ollama.allow_remote", false);

// Security: reject non-localhost unless explicitly allowed
if (!allowRemote && !isLocalhost(ollamaHost)) {
    throw new SecurityException("Remote Ollama hosts require allow_remote: true");
}
```

**Timeout handling**:
```java
// Configurable timeouts for different operations
long chatTimeout = config.getLong("limits.llm_timeout_ms", 300000);  // 5 min
long fileTimeout = config.getLong("limits.file_timeout_ms", 10000);  // 10 sec
```

### Streaming vs Non-Streaming

**Streaming mode** (default for interactive):
- Real-time token display in REPL
- Uses Server-Sent Events (SSE) from Ollama
- Implemented in `dev.loqj.engine.ollama.OllamaStreamingClient`

**Non-streaming mode** (for batch operations):
- Wait for complete response
- Used by `rag-ask` CLI command
- Better for scripting/automation

---

## First-Run & Context Directory

### First-Run Wizard

**Location**: `dev.loqj.app.ui.FirstRunWizard`

**Trigger logic** in `dev.loqj.app.Main`:
```java
if (!hasArgs && FirstRunWizard.shouldRunWizard()) {
    FirstRunWizard.launchWizard();
    return;
}
```

**shouldRunWizard() implementation**:
```java
// Checks for sentinel file existence
public static boolean shouldRunWizard() {
    return !Files.exists(SENTINEL);
}

private static final Path SENTINEL =
    Paths.get(System.getProperty("user.home"), ".loqj", "first_run_done");
```

**Wizard trigger**: Simply checks if `~/.loqj/first_run_done` sentinel file exists. Once created, wizard never runs again.

**Wizard creates**:
- `%USERPROFILE%\.loqj\` directory structure
- Initial `config.yaml` with user preferences
- Sentinel file to prevent re-running
- Model validation guidance (doesn't enforce model availability)

### Context Directory Structure

**Base location**: `%USERPROFILE%\.loqj\`

```
%USERPROFILE%\.loqj\
├── config.yaml          # User configuration overrides
├── indices/              # Lucene indices per workspace
│   ├── <hash1>/          # Workspace 1 index files
│   ├── <hash2>/          # Workspace 2 index files  
│   └── ...
├── cache/                # Embeddings and response caches
│   ├── embeddings.db     # SQLite cache for embeddings
│   └── responses.db      # LLM response cache
├── logs/                 # Application logs
│   └── loqj.log          # Main log file (Logback config)
└── secrets/              # API keys (future expansion)
    └── .gitignore        # Never commit secrets
```

---

## Per-Workspace Indexing

### Current Implementation

**Workspace resolution order** (in `dev.loqj.cli.cmds.StatusCmd.resolveWorkspace()`):
1. `--root` command-line flag
2. `LOQJ_WORKSPACE` environment variable
3. Current working directory

**Per-workspace state**:
- **Separate Lucene indices** in `%USERPROFILE%\.loqj\indices\<hash>\`
- **Independent file inclusion/exclusion** rules
- **Isolated embeddings cache** (keyed by content hash)

**CLI usage patterns**:
```powershell
# Explicit workspace switching
loqj rag-index --root C:\projects\webapp
loqj rag-ask --root C:\projects\webapp "How does auth work?"

# Environment variable approach
$env:LOQJ_WORKSPACE = "C:\projects\webapp"
loqj rag-index        # Uses webapp workspace
loqj rag-ask "How does auth work?"

# Working directory approach  
cd C:\projects\webapp
loqj rag-index        # Indexes current directory
loqj rag-ask "How does auth work?"
```

### Workspace Management Commands

**In REPL** (via `dev.loqj.cli.commands.WorkspaceCommand`):
```
:workspace                    # Show current workspace info (path, index location, doc count)
```

**Note:** The `:workspace` command is information-only. It displays the current workspace path, index directory location, document count, and vector configuration status. There are no subcommands for listing, switching, or cleaning workspaces.
