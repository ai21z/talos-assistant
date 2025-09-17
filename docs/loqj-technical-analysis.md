# LOQ-J Technical Analysis (v0.9.0-beta)

**Version:** 0.9.0-beta  
**Analysis Date:** September 17, 2025  
**Build Timestamp:** 1758094273777  
**Java Version:** Java 21.0.8+12-LTS-250  
**Platform:** Windows 11 amd64

---

## Executive Summary

LOQ-J is a local-first RAG (Retrieval-Augmented Generation) system implemented in Java 21, emphasizing privacy and offline operation. The architecture follows a clean separation of concerns with CLI → Core Services → Storage/LLM layers. Key strengths include robust offline-by-default security, comprehensive caching, and extensible engine SPI. Primary technical debt lies in deprecated engine stubs and some coupling between CLI and core layers.

The codebase demonstrates solid OOP principles with effective use of Strategy, Facade, and Repository patterns. Performance is optimized through virtual threads, caching layers, and efficient Lucene indexing. Test coverage is comprehensive with 11 test suites covering unit, integration, and smoke testing scenarios.

---

## 1) Architecture & Data Flow

### High-Level Component Interaction

```
┌─────────────┐    ┌──────────────┐    ┌─────────────┐
│ CLI Layer   │───▶│ RagService   │───▶│ LuceneStore │
│ (Picocli)   │    │ (Facade)     │    │ (BM25+KNN)  │
└─────────────┘    └──────────────┘    └─────────────┘
       │                   │                   │
       ▼                   ▼                   ▼
┌─────────────┐    ┌──────────────┐    ┌─────────────┐
│ REPL/JLine  │    │ Indexer      │    │ Embeddings  │
│ (Interactive)│    │ (Pipeline)   │    │ (Cached)    │
└─────────────┘    └──────────────┘    └─────────────┘
                           │                   │
                           ▼                   ▼
                   ┌──────────────┐    ┌─────────────┐
                   │ File Walker  │    │ CacheDb     │
                   │ (Concurrent) │    │ (SQLite)    │
                   └──────────────┘    └─────────────┘
```

### Indexing Flow

```
Workspace Root ─▶ FileWalker ─▶ ParserUtil ─▶ Chunker ─▶ Embeddings
      │               │            │           │           │
      │               ▼            ▼           ▼           ▼
      │         Include/Exclude  HTML/PDF   Text Chunks  Vector Cache
      │         Filtering        Parsing    (Overlaps)   (SQLite)
      │                                         │           │
      ▼                                         ▼           ▼
 Index Hash ◄──────────────────── LuceneStore ◄─────── Commit/Refresh
 (~/.loqj/indices/d9efa2f9)      (BM25 + KNN)
```

### Query Flow

```
User Query ─▶ RagService.prepare() ─▶ BM25 Search ─┐
     │              │                      │        │
     │              ▼                      ▼        │
     │         EmbeddingsClient      KNN Search     │
     │              │                      │        │
     │              ▼                      ▼        │
     │         Query Vector           Vector Results │
     │                                     │        │
     │              ┌──────────────────────┴────────┘
     │              ▼
     │         RRF Fusion + MMR
     │              │
     │              ▼
     │         SnippetBuilder
     │              │
     ▼              ▼
LlmClient ◄─── Prompt Construction
     │
     ▼
Final Answer + Citations
```

### Persistence Under `~/.loqj`

- **`indices/{hash}/`** - Lucene index per workspace (SHA-1 of absolute path)
- **`cache.db`** - SQLite database for embeddings and answer caching
- **`secrets/`** - Optional API keys (file-based secret store)
- **Index isolation** ensures multiple workspaces don't interfere

---

## 2) CLI & UX Surface

### Command Structure

**Root Command:** `loqj` (defaults to interactive REPL if no subcommand)

**Subcommands:**
- **Indexing:** `rag-index` - Build/refresh workspace index
- **Querying:** `rag-ask` - One-shot RAG query with citations  
- **Interactive:** `run` - Start REPL with mode switching
- **Management:** `status`, `setup`, `net` (network diagnostics)
- **Utilities:** `version` - Show build info

**Global Options:**
- `--no-logo` - Skip banner display
- `--root <path>` - Override workspace directory
- `--help`, `--version` - Standard help/version

### Multi-Workspace Precedence

1. **`--root` flag** (highest priority)
2. **`LOQJ_WORKSPACE` environment variable**  
3. **Current working directory** (default)

### REPL Behavior

- **Prompt Updates:** Changes based on current mode (`:mode ask|rag|auto`)
- **Commands:** `:help`, `:mode`, `:status`, `:clear`, `:exit`
- **Banner:** Customizable via `--no-logo` flag
- **Index Status:** Real-time feedback on workspace state

### Launchers & Installation

**Windows:** `.bat` wrapper handles classpath and JVM args
**Unix:** Shell script with proper PATH integration  
**Install Scripts:**
- `tools/install-windows.ps1` - Copies to `%LOCALAPPDATA%\Programs\loqj`
- `tools/install-unix.sh` - Copies to `~/.local/bin` (or `/usr/local/bin` with `--sudo`)
- `tools/uninstall-windows.ps1` - Clean removal

---

## 3) Indexing Pipeline

### File Discovery & Filtering

- **Walking Strategy:** Recursive traversal with configurable depth limits
- **Include/Exclude Patterns:** Glob-based filtering via `CfgGlobs`
- **Size Limits:** Per-file and total corpus size caps
- **Type Detection:** Extension-based with MIME type fallback

### Content Processing

- **Parsers:** HTML (Jsoup), PDF (PDFBox), Office docs (Apache POI)
- **Chunking Policy:** Sliding window with configurable overlap
- **Text Extraction:** Preserves structure for citation accuracy
- **Binary Skips:** Early filtering of non-textual content

### Concurrency Model

- **Virtual Threads:** Java 21 virtual threads for I/O-bound operations
- **Semaphore Backpressure:** Controls concurrent file processing
- **Batch Processing:** Groups files for efficient Lucene commits

### Embeddings Integration

- **Vector Enablement:** Configurable via `rag.vectors.enabled`
- **Dimension Probe:** Auto-detects embedding model dimensions
- **Caching:** SQLite-based cache with `CachingEmbeddings` decorator
- **Fallback:** Graceful degradation to BM25-only on embedding failures

### Idempotency & Refresh

- **Content Hashing:** Detects changed files for incremental updates
- **Commit Lifecycle:** Atomic commits with rollback on failure
- **Timing Stats:** Detailed performance metrics via `IndexingStats`

---

## 4) Retrieval & Ranking

### BM25 Configuration

- **Multi-Field Search:** Title, content, and path fields with different boosts
- **Analyzer:** Standard analyzer with stop words and stemming
- **Field Boosts:** Configurable weights per field type

### KNN Vector Search

- **Dimension Handling:** Auto-detects from first embedding
- **HNSW Index:** Lucene's hierarchical navigable small world graphs
- **Fallback Logic:** Continues with BM25-only if vectors unavailable

### Fusion & Reranking

- **RRF (Reciprocal Rank Fusion):** Combines BM25 and KNN results with parameter k=60
- **MMR (Maximal Marginal Relevance):** Diversity-aware reranking with λ=0.7
- **Deduplication:** By document path to avoid duplicate citations

### Snippet Construction

- **Pinned Results:** Ensures top candidates always included
- **Citation Format:** `path#chunkId` for precise source referencing  
- **Truncation:** Respects token limits before LLM processing
- **Context Preservation:** Maintains surrounding text for coherence

---

## 5) LLM Layer & Prompts

### Engine Architecture

- **SPI Design:** `ModelEngineProvider` interface for pluggable backends
- **Active Engine:** Ollama (localhost:11434) as primary implementation
- **Stub Engines:** Deprecated GPT4All and LlamaCpp stubs (marked for removal)

### Prompt Construction

- **System Prompts:** Mode-specific templates (ask vs rag)
- **Context Injection:** Retrieved snippets formatted with citations
- **User Query:** Sanitized and embedded in structured prompt
- **Memory Integration:** Optional session context for rag+memory mode

### Response Processing

- **Sanitization:** Removes `<think>` tags and other LLM artifacts
- **Timeout Handling:** Configurable request timeouts
- **Streaming Support:** Real-time response display in REPL
- **Answer Caching:** Optional caching via `CacheDb`

---

## 6) Caching & Persistence

### CacheDb Schema

```sql
-- Embeddings cache with dimension tracking
CREATE TABLE IF NOT EXISTS embedding_cache(
  key TEXT PRIMARY KEY,
  dim INTEGER NOT NULL,
  vec BLOB NOT NULL,
  ts  INTEGER NOT NULL
);

-- Answer cache
CREATE TABLE IF NOT EXISTS answer_cache(
  key TEXT PRIMARY KEY,
  answer TEXT NOT NULL,
  ts INTEGER NOT NULL
);

-- Session management linked to workspace
CREATE TABLE IF NOT EXISTS sessions(
  id TEXT PRIMARY KEY,
  workspace TEXT NOT NULL,
  created_ts INTEGER NOT NULL
);

-- Memory management for session sketches and entities
CREATE TABLE IF NOT EXISTS memory(
  session_id TEXT PRIMARY KEY,
  sketch TEXT NOT NULL,
  entities TEXT NOT NULL
);
```

### Cache Key Strategy

- **Embeddings:** `{provider}/{model}/{text_hash}` with dimension tracking
- **Eviction:** No automatic eviction (manual cleanup required)

### Index Directory Hashing

- **Path Normalization:** Absolute path converted to SHA-1 hex
- **Cross-Machine Portability:** Deterministic hashing enables sync
- **Isolation:** Prevents workspace cross-contamination

---

## 7) Security & Privacy

### Offline-By-Default Enforcement

- **NetPolicy:** Blocks non-localhost HTTP requests
- **Embedding Security:** Only allows configured embedding endpoints
- **Chat Security:** Restricts LLM communication to approved hosts

### Data Protection

- **No Cloud Dependencies:** All processing occurs locally
- **Logging Redaction:** Sensitive data filtered from logs
- **Secret Management:** File-based secret store with restricted permissions
- **Path Traversal Protection:** Input validation prevents directory escapes

### Attack Surface Analysis

- **HTTP Endpoints:** Limited to localhost:11434 (Ollama)
- **File System Access:** Restricted to workspace and `~/.loqj`
- **Deserialization:** Jackson with type safety controls
- **Process Execution:** No shell command execution in current version

### Known Vulnerabilities

- **SQLite Injection:** Raw SQL in some CacheDb operations (low risk)
- **Path Injection:** Insufficient validation in file walker edge cases
- **Resource Exhaustion:** No built-in limits on memory usage per query

---

## 8) Concurrency, Robustness & Error Handling

### Threading Model

- **Virtual Threads:** Java 21 virtual threads for I/O operations
- **Thread Pools:** Traditional pools for CPU-bound tasks
- **Semaphore Backpressure:** Controls concurrent file processing (default: 8)

### Resource Management

- **Try-With-Resources:** Consistent use for Lucene readers/writers
- **Connection Pooling:** HTTP client connection reuse
- **Memory Management:** Explicit cleanup of large objects

### Failure Modes & Recovery

- **Embed Server Down:** Graceful fallback to BM25-only search
- **Dimension Mismatch:** Automatic vector dimension detection
- **Missing Index:** Clear error messages with setup guidance
- **Partial Index Corruption:** Automatic reindex recommendation

### Retry Logic

- **Network Requests:** Exponential backoff for HTTP failures
- **File I/O:** Retry on transient filesystem errors
- **Database Operations:** Connection retry with timeout

---

## 9) Tests & Coverage

### Test Suite Inventory

1. **RenderEngineSanitizeTest** - Output sanitization validation
2. **CfgGlobsTest** - Configuration glob pattern matching
3. **CfgUtilTest** - Configuration utility functions
4. **EmbeddingsClientSecurityTest** - Network security enforcement
5. **LuceneStoreBm25Test** - BM25 search functionality
6. **ChunkerTest** - Text chunking algorithms
7. **ParserUtilSmokeTest** - File parsing integration
8. **LlmClientStreamParityTest** - LLM streaming consistency
9. **RagFlowSmokeTest** - End-to-end RAG pipeline
10. **SnippetBuilderTest** - Citation and snippet construction
11. **OllamaEngineProviderTest** - Engine provider initialization

### Coverage Analysis

**Strong Coverage:**
- Core configuration loading and validation
- Text processing and chunking algorithms
- Security policy enforcement
- Basic REPL functionality

**Coverage Gaps:**
- Batch embedding operations
- Chat cache hit scenarios
- Multi-workspace precedence logic
- Windows launcher edge cases
- Large file handling limits

### Proposed Additional Tests

1. **ConfigPrecedenceTest** - Verify `--root` > `LOQJ_WORKSPACE` > CWD ordering
2. **BatchEmbeddingTest** - Test concurrent embedding requests with failures
3. **IndexCorruptionRecoveryTest** - Validate automatic reindex on corruption
4. **WindowsLauncherTest** - PATH integration and batch file behavior
5. **LargeCorpusTest** - Memory usage with 10K+ documents
6. **CrossWorkspaceIsolationTest** - Ensure index isolation between workspaces

---

## 10) Performance Hotspots

### Time Distribution Analysis

Based on current logging and architecture:

1. **File Walking & Parsing** - 20-30% (I/O bound)
2. **Embedding Generation** - 40-50% (network bound)
3. **Lucene Indexing** - 15-25% (CPU bound)
4. **Index Commits** - 5-10% (disk bound)

### Current Concurrency Settings

- **File Processing:** 8 concurrent threads (semaphore)
- **HTTP Connections:** Default client pool
- **Lucene Writers:** Single writer per index
- **Virtual Thread Pool:** Unbounded (JVM managed)

### Optimization Opportunities

**Low-Risk Improvements:**
1. **Embedding Batching** - Group multiple texts per API call
2. **Dimension Caching** - Cache model dimensions across sessions
3. **Binary File Early Skip** - Detect binary content before parsing
4. **Commit Timing** - Configurable commit intervals vs immediate

**Medium-Risk Improvements:**
1. **Parallel Chunking** - Process large files in parallel chunks
2. **Index Warmup** - Pre-load frequently accessed index segments
3. **Connection Pooling** - Dedicated HTTP pools per service

### Recommended Ranges

- **Small Workspace (<1K files):** 4-8 concurrent threads
- **Medium Workspace (1K-10K files):** 8-16 concurrent threads  
- **Large Workspace (>10K files):** 16-32 concurrent threads
- **Memory:** 2-8GB heap depending on corpus size

---

## 11) Code Quality & Best Practices

### Package Structure Analysis

**Clean Boundaries:**
- `cli` - Command-line interface and REPL
- `core` - Business logic and services
- `engine` - LLM engine implementations
- `spi` - Service provider interfaces

**Visibility Control:**
- Most classes package-private where appropriate
- Public APIs clearly documented
- SPI interfaces well-defined

### Configuration Management

**Strengths:**
- Centralized configuration loading
- Environment variable precedence
- Strict mode for production deployments
- Centralized configuration loading via `Config` class
- Environment variable precedence (`LOQJ_WORKSPACE`, `LOQJ_STRICT_CONFIG`)
- Inconsistent key naming patterns (camelCase vs snake_case)
- Consistent snake_case naming throughout (includes, excludes, top_k, chunk_chars, embed_concurrency)
- Some hardcoded defaults scattered in code
- Limited validation of config value ranges
- Centralized configuration loading via `Config` class
- Environment variable precedence (`LOQJ_WORKSPACE`, `LOQJ_STRICT_CONFIG`)
- Could benefit from centralized field boost configuration
**Deprecated Components:**
### Technical Debt Items

- GPT4All engine stubs - No longer maintained
**Refactoring Opportunities (Future):**
- Could benefit from centralized field boost configuration
- LlamaCpp engine stubs - Superseded by Ollama
1. **Centralize Field Boosts** - Single configuration point for Lucene field weights
2. **Extract Index Path Helper** - Reduce duplication in path resolution logic
4. **Simplify Mode Strategy** - Reduce complexity in mode switching logic

3. **Simplify Mode Strategy** - Reduce complexity in mode switching logic
## 12) OOP Design Principles & Patterns Audit

### 12a) Package Coupling & Cohesion

#### Package Coupling Matrix

| Package | → cli | → core.* | → engine | → spi | Instability |
|---------|-------|----------|----------|-------|-------------|
| cli | - | High | Medium | Low | High |
| core.rag | - | Medium | Low | Medium | Medium |
| core.index | - | Low | - | Medium | Low |  
| core.embed | - | Low | - | High | Medium |
| core.llm | - | Low | Medium | High | Medium |
| engine.ollama | - | Medium | - | High | Low |
| spi | - | - | - | - | Very Low |

#### Coupling Hotspots

- **CLI → Core Direct Access:** `RunCmd` reaches into `RagService` internals
- **Core Cross-Dependencies:** `RagService` imports from multiple core.* packages
- **Engine Coupling:** Ollama engine directly imports core utilities
- **SPI Leakage:** Some core classes expose SPI types in public APIs

#### Cohesion Assessment

**High Cohesion:**
- `Config` - Single responsibility for configuration management
- `Hash` - Focused utility for hash operations
- `NetPolicy` - Clear security boundary enforcement

**Low Cohesion:**
- `CfgUtil` - Mixed configuration and utility functions
- `RagService` - Handles indexing, retrieval, and LLM coordination
- `Indexer` - File walking, parsing, and Lucene operations

### 12b) SOLID Principles Scorecard

| Principle | Strengths | Risks | Examples |
|-----------|-----------|-------|----------|
| **SRP** | Clean utilities (`Hash`, `Sanitize`), focused value objects | `RagService` handles too many concerns | `Config` (good), `RagService` (mixed) |
| **OCP** | Mode strategy extensible, Engine SPI allows new backends | Hard-coded engine discovery, Mode enum limitations | `ModeController` (good), engine registration (static) |
| **LSP** | Engine implementations properly substitutable | Some SPI methods throw UnsupportedOperationException | `OllamaEngine` vs stub engines |
| **ISP** | Focused SPIs (`ModelEngine`, `Embeddings`) | `CorpusStore` interface may be too broad | `ModelEngine` (focused), `CorpusStore` (mixed) |
| **DIP** | Good use of interfaces for engines and embeddings | Direct Lucene dependencies throughout core | `ModelEngineProvider` (good), `LuceneStore` (concrete) |

### 12c) GRASP Principles Mapping

**Information Expert:** `Config` knows configuration rules, `Hash` knows hashing algorithms  
**Creator:** `RagService` creates `Indexer` (appropriate), `Indexer` creates `LuceneStore` (appropriate)  
**Controller:** `RunCmd` controls REPL flow, `RagService` controls RAG pipeline  
**Low Coupling:** SPI design achieves this between engines and core  
**High Cohesion:** Most utility classes demonstrate this well  
**Polymorphism:** Mode strategy, Engine SPI, Embeddings abstraction  
**Indirection:** `RagService` as facade, `CachingEmbeddings` as decorator  
**Protected Variations:** Engine SPI protects against LLM backend changes

### 12d) Design Patterns Analysis

#### Patterns Currently Used

- **Strategy:** `Mode` implementations (ask, rag, auto)
- **Facade:** `RagService` simplifies complex subsystem interactions  
- **Adapter:** Engine implementations adapt different LLM APIs
- **Repository:** `LuceneStore` encapsulates corpus storage
- **Decorator:** `CachingEmbeddings` adds caching to base embedding client
- **Command:** REPL commands (`:help`, `:mode`, `:status`)
- **Policy Objects:** `NetPolicy` encapsulates security rules
- **Value Objects:** `Config`, `IndexingStats`, `Answer` records

#### Pattern Extension Candidates

1. **Factory/Builder Pattern** - Complex engine configuration and model selection
2. **Observer Pattern** - Mode change notifications for UI updates  
3. **Pipeline Pattern** - Explicit indexing pipeline with pluggable stages
4. **Null Object Pattern** - Disabled vector operations, offline modes
5. **Specification Pattern** - Complex retrieval criteria composition
6. **Module/Plugin Architecture** - Dynamic engine loading and configuration

### 12e) Proposals Without Code Changes

#### Package Ownership & Dependencies

**Proposed Architecture Rules:**
- CLI layer may only access core via `RagService` facade
- Core packages should minimize cross-dependencies
- Engine implementations may only use SPI + minimal core utilities
- SPI packages must be dependency-free (only JDK + minimal external)

#### Public API Surface Documentation

**External Extension Points:**
- `ModelEngineProvider` - Add new LLM backends
- `ModelEngine` - Implement LLM communication protocol
- `Embeddings` - Custom embedding providers
- `BackendProcessManager` - Process lifecycle management

**Internal APIs (subject to change):**
- All classes in `core.*` packages except SPI
- CLI implementation details
- Configuration internals

#### Design Rules Document

1. **Separation of Concerns:** CLI handles user interaction, Core handles business logic, Engines handle external services
2. **Dependency Direction:** CLI → Core → SPI ← Engine (never Engine → Core directly)
3. **Resource Management:** All I/O operations must use try-with-resources
4. **Security First:** All network operations must go through NetPolicy
5. **Fail-Safe Defaults:** System must work with minimal configuration

#### Future Refactoring Plan (Conceptual)

**Phase 1 (Low Risk):**
- Extract `IndexPathResolver` utility class
- Centralize field boost configuration in single location
- Create `ConfigValidator` for range checking
- Document public vs internal API boundaries

**Phase 2 (Medium Risk):**
- Extract `CorpusStoreReader` and `CorpusStoreWriter` interfaces
- Create `EmbeddingBatchProcessor` for improved performance
- Implement `IndexingPipeline` with pluggable stages
- Add `ModelSelectionStrategy` for automatic model choosing

**Phase 3 (Higher Risk):**
- Restructure core packages for cleaner boundaries
- Implement plugin architecture for dynamic engine loading
- Create configuration validation framework
- Add comprehensive health check subsystem

---

## 13) Risks & Recommendations

### Top 5 Risks (Impact × Likelihood)

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **Deprecated Engine Stubs** | Medium | High | Remove GPT4All/LlamaCpp stubs in next release |
| **SQLite Injection Vulnerabilities** | High | Low | Parameterize all CacheDb queries |
| **Memory Exhaustion (Large Corpora)** | High | Medium | Implement corpus size limits and streaming |
| **Index Corruption Recovery** | Medium | Medium | Add automatic corruption detection and repair |
| **Network Security Bypass** | High | Low | Comprehensive NetPolicy audit and testing |

### Prioritized Backlog

#### Now (High Priority, Next Sprint)

- **[S] Remove Deprecated Engine Stubs** - Clean up GPT4All/LlamaCpp code
- **[S] Document Public API Surface** - Clear internal vs external boundaries  
- **[M] Add ConfigValidator** - Range checking and validation framework
- **[L] Comprehensive NetPolicy Testing** - Security boundary verification

#### Next (Medium Priority, Next Quarter)

- **[M] Implement Embedding Batching** - Improve performance for large indexing
- **[M] Add Index Corruption Recovery** - Automatic detection and repair
- **[L] Create Indexing Pipeline Framework** - Pluggable processing stages
- **[S] Centralize Field Boost Configuration** - Single source of truth

#### Later (Lower Priority, Future Releases)

- **[L] Plugin Architecture for Engines** - Dynamic engine loading
- **[M] Cross-Platform Launcher Testing** - Windows/Unix edge cases
- **[L] Health Check Subsystem** - Comprehensive system monitoring
- **[S] Configuration Naming Standardization** - Consistent key patterns

**Effort Legend:** S=Small (1-3 days), M=Medium (1-2 weeks), L=Large (1+ months)

### Documentation vs Code Items

**Doc-Only Requirements:**
- Public API surface documentation
- Architecture decision records
- Configuration precedence rules
- Security model documentation

**Code Changes Required:**
- Deprecated stub removal
- SQLite injection fixes
- Memory limit enforcement
- Embedding batch processing

---

## Appendix

### A) Command Inventory

#### Primary Commands
- `loqj` - Interactive REPL (default)
- `loqj rag-index [--root <path>]` - Build/refresh index
- `loqj rag-ask [--root <path>] "<query>"` - One-shot RAG query
- `loqj status [--verbose]` - System status and configuration
- `loqj setup` - First-time configuration wizard

#### REPL Commands  
- `:help` - Show available commands
- `:mode <ask|rag|auto>` - Switch interaction mode
- `:status` - Show current workspace status
- `:clear` - Clear screen
- `:exit` - Exit REPL

#### Utility Commands
- `loqj version` - Show build information
- `loqj net` - Network connectivity diagnostics

### B) Configuration Keys & Precedence

#### Precedence Order (Highest to Lowest)
1. Command-line flags (`--root`, `--no-logo`)
2. Environment variables (`LOQJ_WORKSPACE`, `LOQJ_STRICT_CONFIG`)
3. Config file (`config/default-config.yaml`)
4. Built-in defaults

#### Key Configuration Sections
```yaml
rag:
  top_k: 6
  vectors:
    enabled: true
  limits:
    max_files: 10000
    max_file_size_mb: 100
    
llm:
  host: "http://127.0.0.1:11434"
  model: "qwen2.5:7b"
  timeout_seconds: 30

embeddings:
  model: "bge-m3"
  cache_ttl_hours: 168
```

### C) ~/.loqj Persistence Map

```
~/.loqj/
├── indices/
│   ├── d9efa2f9/          # SHA-1 of workspace path
│   │   ├── segments_*     # Lucene index files
│   │   └── write.lock     # Index write lock
│   └── a1b2c3d4/          # Another workspace
├── cache.db               # SQLite embeddings/answer cache
├── config/
│   └── user-config.yaml   # User overrides (optional)
└── secrets/
    └── api-keys.json      # External service keys (optional)
```

### D) Known Limitations & Open Questions

#### Current Limitations
- No automatic cache eviction policy
- Limited batch processing for embeddings  
- Single-threaded Lucene writing
- No cross-workspace query capabilities
- Windows-specific path handling edge cases

#### Open Questions
- **Multi-tenant Support:** Should LOQ-J support shared indices?
- **Remote Index Sync:** Cloud backup/sync capabilities?
- **Plugin Architecture:** Dynamic engine loading vs static registration?
- **Memory Limits:** Configurable heap limits per operation?
- **Audit Trail:** Should all queries be logged for compliance?

#### Future Considerations
- **Distributed Indexing:** Multi-machine corpus processing
- **Real-time Updates:** File system watching for incremental updates  
- **Advanced RAG:** Graph-based retrieval, multi-hop reasoning
- **Model Fine-tuning:** Local model training on workspace data
- **Enterprise Features:** RBAC, audit logging, compliance reporting

---

*Analysis completed: September 17, 2025*  
*LOQ-J v0.9.0-beta - Build 1758094273777*
