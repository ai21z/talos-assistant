# LOQ-J Technical Analysis

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
- [Multi-Workspace Support](#multi-workspace-support)
- [Test Coverage & Limits](#test-coverage--limits)
- [Operational Notes](#operational-notes)

---

## Architecture Overview

LOQ-J follows a layered architecture with clear separation of concerns:

```
┌─────────────────────────────────────────┐
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

### Data Flow

1. **CLI Entry** → `dev.loqj.app.Main` → Picocli command parsing
2. **Interactive Mode** → `dev.loqj.cli.cmds.RunCmd` → JLine REPL
3. **Mode Routing** → `dev.loqj.cli.modes.ModeController` → Strategy pattern
4. **RAG Query** → `dev.loqj.core.rag.RagService` → Index search + LLM generation
5. **Result Rendering** → `dev.loqj.cli.repl.RenderEngine` → Terminal output

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

**Wizard creates**:
- `%USERPROFILE%\.loqj\` directory structure
- Initial `config.yaml` with user preferences
- Model validation (checks if BGE-M3 and chat model are available)

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

### Multi-Workspace Index Management

**Workspace identification**: `dev.loqj.core.IndexPathResolver`

```java
// Hash-based workspace identification
String workspaceHash = DigestUtils.sha256Hex(workspacePath.toString());
Path indexPath = userDataDir.resolve("indices").resolve(workspaceHash);
```

**Benefits**:
- **Isolation**: Each workspace has separate Lucene index
- **Performance**: No cross-contamination between projects  
- **Storage**: Deduplication via content hashing
- **Cleanup**: Easy to identify and remove unused indices

---

## Multi-Workspace Support

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
:workspace                    # Show current workspace
:workspace list               # List known workspaces  
:workspace switch <path>      # Change active workspace
:workspace clean <path>       # Remove workspace index
```

---

## Test Coverage & Limits

### Test Structure

**Test packages** mirror main packages:
```
src/test/java/dev/loqj/
├── cli/repl/                 # REPL command testing
├── core/                     # Core logic unit tests
│   ├── CfgUtilTest.java      # Configuration parsing
│   ├── CfgGlobsTest.java     # File pattern matching  
│   ├── index/                # Indexing tests
│   ├── embed/                # Embeddings client tests
│   ├── rag/                  # RAG pipeline tests
│   └── search/               # Search & retrieval tests
├── engine/ollama/            # Ollama client tests
└── bench/                    # Performance benchmarks
```

### Security & Injection Tests

**SQL injection protection** (`dev.loqj.core.cache.CacheDbSqlInjectionTest`):
- Tests SQLite cache against malicious inputs
- Validates parameterized queries

**Content sanitization** (`dev.loqj.cli.repl.RenderEngineSanitizeTest`):
- ANSI escape sequence filtering  
- Output sanitization for terminal safety

**Network security** (`dev.loqj.core.embed.EmbeddingsClientSecurityTest`):
- Localhost-only validation for Ollama
- Remote host blocking tests

### Performance Tests

**Batch embeddings** (`dev.loqj.core.embed.BatchEmbeddingsPerformanceTest`):
- Concurrency scaling tests
- Memory usage validation

**Lucene BM25** (`dev.loqj.core.index.LuceneStoreBm25Test`):
- Search performance benchmarks
- Index size vs. query speed trade-offs

### Known Limits & Constraints

**From configuration** (`src/main/resources/config/default-config.yaml`):
```yaml
limits:
  top_k_max: 100                   # Maximum retrieval count
  response_max_chars: 10485760     # 10MB response size limit
  dir_depth_max: 10                # Directory traversal depth
  file_bytes_max: 20000            # 20KB max file size
  file_lines_max: 500              # 500 line limit per file
  dir_entries_max: 1000            # Max files per directory
  llm_timeout_ms: 300000           # 5 minute LLM timeout
  file_timeout_ms: 10000           # 10 second file I/O timeout  
  rate_per_sec: 10                 # 10 requests per second limit
```

**Platform-specific behavior**:
- **Windows**: Case-insensitive file glob matching (`dev.loqj.core.index.IndexerCaseTest`)
- **Linux/macOS**: Case-sensitive file matching
- **Vector API**: Requires Java 21+ (`--add-modules jdk.incubator.vector`)

---

## Operational Notes

### Index Storage & Performance

**Index file structure**:
```
%USERPROFILE%\.loqj\indices\<workspace-hash>\
├── _0.cfe, _0.cfs         # Lucene segment files
├── _0_Lucene90_0.dvd      # DocValues (metadata)
├── _0_Lucene90_0.vec      # Vector index (HNSW)
├── segments_1             # Segment metadata
└── write.lock             # Write synchronization
```

**Typical index sizes**:
- **Small project** (< 100 files): 1-10 MB
- **Medium project** (100-1000 files): 10-100 MB  
- **Large project** (1000+ files): 100MB-1GB
- **Enterprise** (10k+ files): 1GB+ (consider workspace splitting)

### Memory Usage Patterns

**Indexing phase**:
- **File parsing**: 50-200 MB working set
- **Embeddings generation**: 100-500 MB (depends on batch size)
- **Lucene writing**: 100-300 MB buffer space

**Query phase**:
- **Base memory**: 50-100 MB
- **Per-query overhead**: 10-50 MB (depends on top-K)
- **High K values** (K > 20): Can use 200+ MB for context assembly

### Cache Behavior

**Embeddings cache** (`dev.loqj.core.cache.EmbeddingsCache`):
- **Storage**: SQLite database (`%USERPROFILE%\.loqj\cache\embeddings.db`)
- **Key**: SHA-256 hash of text content
- **Persistence**: Survives restarts, shared across workspaces
- **Size management**: No automatic cleanup (manual `rm` if needed)

**Response cache** (if enabled):
- **Storage**: SQLite database (`%USERPROFILE%\.loqj\cache\responses.db`)
- **Key**: Hash of (model + prompt + parameters)
- **TTL**: Configurable expiration (default: none)

### Logging & Debugging

**Log configuration**: `src/main/resources/config/logback.xml`

**Log levels**:
- **INFO**: Normal operation messages
- **DEBUG**: Enable via `:debug on` in REPL or `-Dloqj.debug=true`
- **TRACE**: Detailed Lucene and HTTP client logs

**Log file location**: `%USERPROFILE%\.loqj\logs\loqj.log`

**Debug output includes**:
- Retrieved snippet content and scores
- Embeddings generation timing
- HTTP request/response details (Ollama)
- Index statistics and query performance

### Production Deployment Considerations

**Resource requirements**:
- **CPU**: 4+ cores recommended for concurrent embeddings
- **RAM**: 8GB minimum, 16GB+ for large workspaces
- **Storage**: SSD strongly recommended for index performance
- **Network**: Local Ollama only (security best practice)

**Scaling recommendations**:
- **Large teams**: Consider dedicated Ollama instance per developer
- **Large codebases**: Split into focused workspaces by component/service
- **CI/CD integration**: Use `--bm25-only` for faster indexing in automation

---

**LOQ-J Technical Analysis** - Version `v0.9.0-beta` • Commit `ec2f6e9`
