# LOQ-J Modernization Plan — Technical Evaluation

**Branch baseline:** `v0.9.0-beta-dev` (commit `7617773`)
**Date:** 2026-03-30
**Author:** Technical audit of current codebase + evaluation of proposed plan

---

## A. Current Architecture Audit

### Package Map (114 source files, 22 test files)

| Package | Files | Responsibility |
|---------|-------|---------------|
| `dev.loqj.app` | 2 | Entry point (`Main`) + JavaFX first-run wizard |
| `dev.loqj.cli.cmds` | 10 | Picocli CLI subcommands (index, ask, run, diagnose...) |
| `dev.loqj.cli.commands` | 22 | REPL colon-commands (`:k`, `:files`, `:grep`, `:mode`...) |
| `dev.loqj.cli.modes` | 8 | REPL mode strategies (rag, ask, dev, web, auto) |
| `dev.loqj.cli.repl` | 10 | REPL infra (router, pipeline, context, render, session) |
| `dev.loqj.core` | 4 | Config, CfgUtil, Audit, IndexPathResolver |
| `dev.loqj.core.cache` | 1 | SQLite cache (embeddings, answers, sessions, memory) |
| `dev.loqj.core.embed` | 3 | Embeddings client, caching decorator, batch interface |
| `dev.loqj.core.engine` | 1 | EngineRegistry (ServiceLoader discovery) |
| `dev.loqj.core.index` | 3 | Indexer, LuceneStore, IndexingStats |
| `dev.loqj.core.ingest` | 4 | FileWalker, ParserUtil, Chunker, ParsedChunk |
| `dev.loqj.core.llm` | 3 | LlmClient, CachingLanguageModel, OllamaModels |
| `dev.loqj.core.net` | 1 | NetPolicy |
| `dev.loqj.core.rag` | 4 | RagService, MemoryManager, MemoryPrompts, PromptValidator |
| `dev.loqj.core.retriever` | 1 | Bm25KnnRetriever |
| `dev.loqj.core.search` | 2 | Retriever (RRF+MMR), SnippetBuilder |
| `dev.loqj.core.secret` | 2 | FileSecretStore, SecretStore interface |
| `dev.loqj.core.security` | 2 | Redactor, Sandbox |
| `dev.loqj.core.spi` | 4 | Core SPI interfaces (CorpusStore, Embeddings, LanguageModel, RetrieverEngine) |
| `dev.loqj.core.util` | 2 | Hash, Sanitize |
| `dev.loqj.spi` | 4 | Engine SPI (ModelEngine, ModelEngineProvider, ModelCatalog, BackendProcessManager) |
| `dev.loqj.spi.types` | 7 | SPI value types (ChatRequest, TokenChunk, Capabilities...) |
| `dev.loqj.engine.ollama` | 3 | Ollama engine implementation |
| `dev.loqj.engine.stubs.*` | 6 | Deprecated stub engines (GPT4All, LlamaCpp) |

### Current Strengths

1. **Solid Lucene foundation.** `LuceneStore` wraps Lucene 10.x correctly with BM25 + KNN float vectors, NRT `SearcherManager`, incremental indexing via file hashing, and multi-field boosted queries (name > pathtok > text).

2. **SPI architecture exists.** Two SPI layers: `dev.loqj.core.spi` (CorpusStore, Embeddings, LanguageModel, RetrieverEngine) and `dev.loqj.spi` (ModelEngine, ModelEngineProvider). ServiceLoader discovery works for engine backends.

3. **Security posture is real.** Sandbox (workspace-boundary enforcement, symlink-aware), Redactor, Sanitize (ANSI/control/HTML/think-tag stripping), localhost-only embedding policy, rate limiting, input length caps.

4. **Config system is layered.** Classpath defaults -> user YAML -> ENV overrides -> CLI flags. Strict mode, default tracking, report snapshot. This is better than most CLI tools.

5. **REPL is structured.** Clean Mode/Command separation, LineClassifier, ExecutionPipeline, RenderEngine. Context record bundles all runtime deps. ModeController does intent-based routing for "auto" mode.

6. **Chunker is markdown/code-aware.** Respects code fences and headings. Overlap support. Not naive fixed-window.

7. **Embedding cache is persistent.** SQLite-backed via CacheDb. Saves re-embedding on incremental reindex. Dimension caching too.

8. **RRF fusion implemented.** Both `Retriever.fuseRrf()` and `Bm25KnnRetriever` do proper Reciprocal Rank Fusion.

### Current Weaknesses

1. **Two parallel retrieval implementations.** `Retriever` (in `core.search`) and `Bm25KnnRetriever` (in `core.retriever`) both do RRF. `RagService.prepare()` calls `Retriever.fuseRrf()` + `Retriever.mmr()` directly. `Bm25KnnRetriever` implements the `RetrieverEngine` SPI but is never used by the main flow. The SPI is defined but orphaned.

2. **`RagService` is a god object.** It combines: lazy indexing, retrieval orchestration, LLM calling, prompt assembly, citation building, session memory. 238 lines doing 6 different jobs.

3. **No reranking.** MMR in `Retriever.mmr()` is just path dedup, not actual Maximal Marginal Relevance. The `lambda` parameter is reserved but unused. No second-stage scoring.

4. **No retrieval pipeline abstraction.** The retrieval flow (query -> BM25 + KNN -> fuse -> rerank -> pack) is hardcoded inside `RagService.prepare()` and `RagMode.handle()`. No way to compose, swap, or trace steps.

5. **Chunking is format-blind.** `Chunker` handles markdown headings and code fences but treats Java/Python/Go the same as prose. No AST-aware splitting, no function-boundary detection, no structured metadata extraction (language, function name, class).

6. **`ParserUtil` is minimal.** HTML is stripped with regex (not Jsoup, even though Jsoup is a dependency). PDF and Office parsing are listed as deps in build.gradle but never called. Dead dependencies.

7. **`LlmClient` has dual transport modes.** PLACEHOLDER (no backend, deterministic) vs ENGINE (real Ollama). Tests depend on PLACEHOLDER behavior. The modes are tightly coupled with sanitization logic. Hard to test the real pipeline without an Ollama server.

8. **Two SPI layers with unclear boundary.** `dev.loqj.core.spi` defines CorpusStore/Embeddings/LanguageModel/RetrieverEngine. `dev.loqj.spi` defines ModelEngine/ModelEngineProvider/ModelCatalog. Both exist, neither fully governs the system. `LlmClient` uses `EngineRegistry` which uses `dev.loqj.spi`, but `RagService` uses `LlmClient` + `LuceneStore` directly without touching `RetrieverEngine`.

9. **Test coverage is thin.** 22 tests for 114 source files (19% file ratio). No tests for: RagService, Indexer end-to-end, LuceneStore KNN, EngineRegistry, ModeController routing, Context builder, most commands. Tests that exist are good quality but gaps are wide.

10. **Dead/deprecated code.** `RagMemoryMode` (deprecated, just delegates). `WebMode` (stub, always returns "reserved"). `AutoMode` (empty, routing is in ModeController). Stub engines in `engine.stubs.*` (deprecated, never loaded via ServiceLoader). `OllamaModels` in `core.llm` (unclear purpose vs `OllamaCatalog`).

11. **No metadata in chunks.** `ParsedChunk` stores `id, path, text, fileHash, chunkId` but no language, no function name, no heading context, no line range. This blocks metadata-filtered retrieval.

12. **Context packing is split across classes.** `SnippetBuilder.packWithPinned()` does budget-aware packing. `PromptValidator.validateAndTrim()` does token-budget trimming. `RagMode.handle()` does pinned-file extraction + comparison intent. Three classes participate in prompt assembly with no unifying abstraction.

13. **Token estimation is crude.** `chars/4` heuristic in `PromptValidator`. No actual tokenizer, no model-specific estimation.

### Technical Debt

- Duplicate SQLite JDBC dep in `build.gradle.kts` (both `3.45.1.0` and `3.46.0.0`)
- `Indexer.reindex()` uses reflection to call its own `index()` method (unnecessary, historical artifact)
- `RunCmd` has an inner `Limits` class duplicating `dev.loqj.cli.repl.Limits` semantics
- `Config.ensureDefaults()` is 80+ lines of imperative map-building (fragile, hard to extend)
- JavaFX dependency for first-run wizard only (heavy dep for a CLI tool)
- `OllamaEngine` does manual JSON escaping instead of using Jackson (which is already a dep)

### Docs vs Code Mismatches

- README lists `LOQJ_WORKSPACE` and `LOQJ_OLLAMA_HOST` env vars, but `Config` only reads `LOQJ__*` prefix format
- README says `file_bytes_max: 20000` in config but `default-config.yaml` has `200000`
- `web` mode and `rag+memory` mode are documented as non-functional, which is accurate

---

## B. Main Problems Blocking LOQ-J Evolution

### B1. No retrieval pipeline abstraction

The single biggest blocker. Today, retrieval logic is smeared across `RagService.prepare()`, `Retriever`, `SnippetBuilder`, `PromptValidator`, and `RagMode`. You cannot swap strategies, add reranking, trace retrieval, or test retrieval independently of LLM calling.

**Impact:** Blocks hybrid retrieval, reranking, query rewriting, retrieval traces, and any future MCP/server exposure.

### B2. `RagService` conflates retrieval with generation

`RagService.ask()` does: ensure index -> retrieve -> check net policy -> read prompt -> validate tokens -> call LLM -> return. The retrieval result is inaccessible without triggering generation. Any external consumer would need retrieval decoupled from LLM invocation.

### B3. The `RetrieverEngine` SPI is orphaned

`Bm25KnnRetriever` implements `RetrieverEngine` but is never called. `RagService` constructs its own retrieval by calling `LuceneStore` directly. Either the SPI should govern the flow or it should be removed.

### B4. Chunks lack structured metadata

`ParsedChunk` has no `language`, `functionName`, `className`, `headingContext`, `lineStart`, `lineEnd`. This blocks metadata-filtered retrieval, code-aware chunking, and structured citations.

### B5. No extensible ingestion pipeline

`ParserUtil.smartParse()` is a monolithic switch on extension. No parser registry, no plugin mechanism.

### B6. Core is not separable from CLI

No clean API boundary like `KnowledgeEngine.builder().index(path).query("x").results()`. Everything flows through `RagService` wired to Config directly.

---

## C. Proposed Target Architecture

### What stays CLI
- `dev.loqj.app` - entry point, wizard
- `dev.loqj.cli.*` - all REPL, commands, modes, Picocli subcommands

### What becomes reusable core library
- `dev.loqj.core.ingest` - parsing, chunking, file walking (with parser registry)
- `dev.loqj.core.index` - LuceneStore, Indexer
- `dev.loqj.core.retrieval` (NEW) - pipeline abstraction, stages, traces
- `dev.loqj.core.rerank` (NEW) - reranking interfaces and implementations
- `dev.loqj.core.context` (NEW) - context packing, prompt assembly, token budgeting
- `dev.loqj.core.embed` - stays
- `dev.loqj.core.spi` - cleaned up, one authoritative SPI layer

### Local service/MCP layer
**Not yet.** Design the retrieval pipeline so it *could* be exposed later, but don't build the server now. MCP adapter belongs in Phase 2 at earliest.

### Module strategy
Do NOT split into multiple Gradle submodules. The codebase is ~7K lines. Enforce separation via package boundaries and a clear API surface. Multi-module when you have a real second consumer.

---

## D. Proposed Package Structure

```
dev.loqj.core.ingest/            # PARSING + CHUNKING (enhanced)
dev.loqj.core.index/             # STORAGE (stays)
dev.loqj.core.retrieval/         # NEW: RETRIEVAL PIPELINE
  RetrievalPipeline, RetrievalStage, RetrievalContext, RetrievalTrace
  stages/ BM25Stage, KnnStage, RrfFusionStage, DedupStage, RerankerStage
dev.loqj.core.rerank/            # NEW: RERANKING
  Reranker, NoOpReranker, CrossEncoderReranker (future)
dev.loqj.core.context/           # NEW: CONTEXT ASSEMBLY
  ContextPacker, TokenBudget, ContextResult
dev.loqj.core.embed/             # STAYS
dev.loqj.core.cache/             # STAYS
dev.loqj.core.search/            # DEPRECATED -> absorbed into retrieval
dev.loqj.core.retriever/         # DELETED -> absorbed into retrieval stages
dev.loqj.core.rag/               # SLIMMED: thin orchestrator only
dev.loqj.core.llm/               # STAYS
dev.loqj.core.spi/               # UNIFIED: one SPI layer
dev.loqj.engine.ollama/          # STAYS
dev.loqj.engine.stubs/           # DELETED
```

---

## E. Phased Roadmap

### Phase 0: Cleanup / Foundation

**Goal:** Remove dead weight, fix build, close test gaps, prepare for pipeline work.

**Scope:**
- Delete `engine.stubs.*` (6 files), `RagMemoryMode`, `AutoMode`
- Fix duplicate SQLite JDBC dep, remove unused PDFBox/POI deps (or wire them)
- Remove reflection hack in `Indexer.reindex()`
- Deduplicate `RunCmd.Limits` vs `dev.loqj.cli.repl.Limits`
- Fix `OllamaEngine` to use Jackson for JSON
- Add tests for `RagService.prepare()`, `ModeController.route()`, `LuceneStore` BM25+KNN, `EngineRegistry`
- Fix docs/README env var mismatches

**What NOT to do:** Don't refactor `RagService`, don't move packages, don't add new abstractions.

### Phase 1: "RAG Done Properly"

**Goal:** Retrieval pipeline abstraction, reranking hook, retrieval traces, improved chunking.

**Scope:**
1. `RetrievalPipeline` + `RetrievalStage` + `RetrievalContext` + `RetrievalTrace`
2. Concrete stages: BM25, KNN, RRF Fusion, Dedup, Reranker (absorbs existing code)
3. Wire `RagService.prepare()` through pipeline; delete `Retriever` + `Bm25KnnRetriever`
4. `ContextPacker` unifying `SnippetBuilder` + `PromptValidator`
5. Chunk metadata (language, lineStart/lineEnd) in `ParsedChunk` + Lucene stored fields
6. `Reranker` interface + `NoOpReranker` default
7. Retrieval trace in `:debug` and `DiagnoseCmd`

**What NOT to do:** Don't build cross-encoder reranking, query rewriting, Gradle submodules, MCP, or graph storage.

### Phase 2: Agentic Retrieval

**Goal:** Query improvement, real reranking, MCP readiness.

**Scope:** Query rewriting/decomposition stages, cross-encoder reranker, metadata-filtered retrieval, code-aware chunking, parser registry, programmatic API surface (`LoqjEngine.builder()`), MCP adapter skeleton.

### Phase 3: Optional Graph Augmentation

**Goal:** Graph-assisted retrieval for relationship-heavy codebases.

**Scope:** Call-graph/import-graph extraction, SQLite adjacency storage, graph expansion stage.

### Phase 4: Optional Schema / Knowledge Mode

**Goal:** Domain-specific structured reasoning over schemas/APIs/DB models.

---

## F. First Implementation Slice

### Recommendation: Retrieval Pipeline Abstraction

Build `RetrievalPipeline`, `RetrievalStage`, `RetrievalContext`, `RetrievalTrace`, and four concrete stages (BM25, KNN, RRF, Dedup). Wire through `RagService.prepare()`. Add `NoOpReranker` as the reranker slot.

**Why this is the keystone:**
1. Absorbs two redundant implementations into one composable system
2. Creates slots for reranking (Phase 1), metadata filtering (Phase 2), query rewriting (Phase 2)
3. Produces `RetrievalTrace` improving `:debug` output immediately
4. Makes `RagService.prepare()` ~10 lines instead of ~50
5. 100% testable without Ollama (mock stores)
6. Low-regret: pipeline-of-stages is universally useful even if architecture pivots

**Size:** ~8 new files, ~400 lines new code, ~100 lines removed. No new deps.

---

## G. Concrete File-by-File Refactor Suggestions

### Deletions (Phase 0)

| File | Action | Reason |
|------|--------|--------|
| `engine/stubs/gpt4all/*` (3 files) | Delete | Deprecated, never loaded via ServiceLoader, returns mock data |
| `engine/stubs/llamacpp/*` (3 files) | Delete | Same as above |
| `cli/modes/RagMemoryMode.java` | Delete | Deprecated thin wrapper, just delegates to RagMode |
| `cli/modes/AutoMode.java` | Delete | Empty class, routing lives in ModeController |
| `core/retriever/Bm25KnnRetriever.java` | Delete (Phase 1) | Absorbed into pipeline stages |
| `core/search/Retriever.java` | Delete (Phase 1) | Absorbed into pipeline stages |

### Modifications

| File | Change | Phase |
|------|--------|-------|
| `build.gradle.kts` | Remove duplicate sqlite-jdbc dep (line 81 duplicates line 62). Remove PDFBox + POI if not wiring them. | 0 |
| `Indexer.reindex()` | Replace reflection with direct `index(root)` call | 0 |
| `RunCmd.java` | Remove inner `Limits` class, use `dev.loqj.cli.repl.Limits` | 0 |
| `ModeController.defaultController()` | Remove `RagMemoryMode` and `AutoMode` from registration | 0 |
| `WebMode.java` | Either delete or keep unregistered. If kept, don't register in `defaultController()` | 0 |
| `OllamaEngine.java` | Replace manual `esc()`/`unesc()` JSON with Jackson `ObjectMapper` | 0 |
| `Config.ensureDefaults()` | Consider extracting to a `ConfigDefaults` class with declarative structure | 0 |
| `RagService.prepare()` | Rewrite to delegate to `RetrievalPipeline.execute()` | 1 |
| `RagService.ask()` | Extract LLM call into a separate method, slim down to orchestrator | 1 |
| `SnippetBuilder.java` | Move packing logic into `ContextPacker`, keep as legacy alias | 1 |
| `PromptValidator.java` | Absorb into `ContextPacker` or `TokenBudget` | 1 |
| `ParsedChunk.java` | Add optional `ChunkMetadata` field (language, lineStart, lineEnd) | 1 |
| `LuceneStore.java` | Add stored fields for chunk metadata when present | 1 |
| `ParserUtil.java` | Refactor into `Parser` interface + per-format implementations | 2 |
| `Chunker.java` | Add code-aware splitting (detect function boundaries for Java/Python) | 2 |

### New Files (Phase 1)

| File | Purpose |
|------|---------|
| `core/retrieval/RetrievalPipeline.java` | Pipeline builder and executor |
| `core/retrieval/RetrievalStage.java` | Stage interface |
| `core/retrieval/RetrievalContext.java` | Immutable context passed through stages |
| `core/retrieval/RetrievalTrace.java` | Per-stage timing and decision log |
| `core/retrieval/ScoredCandidate.java` | Candidate record (path, score, source stage) |
| `core/retrieval/stages/BM25Stage.java` | BM25 retrieval from LuceneStore |
| `core/retrieval/stages/KnnStage.java` | KNN retrieval from LuceneStore |
| `core/retrieval/stages/RrfFusionStage.java` | Reciprocal Rank Fusion |
| `core/retrieval/stages/DedupStage.java` | Path deduplication |
| `core/retrieval/stages/RerankerStage.java` | Delegates to Reranker interface |
| `core/rerank/Reranker.java` | Reranker interface |
| `core/rerank/NoOpReranker.java` | Passthrough default |
| `core/context/ContextPacker.java` | Unified context assembly |
| `core/context/TokenBudget.java` | Token estimation and budget |
| `core/context/ContextResult.java` | Packed context + provenance |

### Test Gaps to Close (Phase 0)

| Test needed | What it covers |
|------------|----------------|
| `RagServicePrepareTest.java` | Mock LuceneStore, verify retrieval flow returns expected candidates |
| `ModeControllerRoutingTest.java` | Verify auto-mode routing (dev before rag before ask), hint override |
| `LuceneStoreKnnTest.java` | Index with vectors, query KNN, verify results |
| `EngineRegistryTest.java` | ServiceLoader picks up OllamaEngineProvider, select/engine cycle |
| `ContextBuilderTest.java` | Build Context with all deps, verify wiring |
| `RetrievalPipelineTest.java` (Phase 1) | Mock stages, verify ordering, trace recording |

### Config/Resource Cleanup

| Item | Action |
|------|--------|
| `default-config.yaml` | Align `file_bytes_max` value with README (decide: 20KB or 200KB) |
| `model-registry.yaml` | Verify still useful or delete |
| `prompts/system.txt` | Demands JSON output format - conflicts with rag-system.txt. Clarify when each is used. |
| `META-INF/services/` | Remove references to stub engine providers if stubs are deleted |

### Dependency Cleanup

| Dependency | Action |
|-----------|--------|
| `sqlite-jdbc` | Remove the `3.46.0.0` duplicate (keep `3.45.1.0` from `sqliteJdbcVersion` property, or bump the property) |
| `pdfbox 3.0.3` | Remove unless you wire PDF parsing in Phase 2 |
| `poi-ooxml 5.4.0` | Remove unless you wire DOCX parsing in Phase 2 |
| `javafx-*` | Consider making optional (only for FirstRunWizard) |
| `jsoup 1.18.1` | Wire into `ParserUtil` for HTML (replace regex) or remove |

---

## H. Risks, Open Questions, and What to Validate Next

### Risks

1. **Pipeline overhead for simple queries.** Creating pipeline objects for every query adds allocation. Mitigation: stages are stateless, pipeline is reusable, overhead is nanoseconds vs milliseconds for Lucene/LLM.

2. **Breaking existing CLI behavior.** `RagMode` and `RagService` are tightly coupled. Refactoring `prepare()` could change retrieval ordering or scores. Mitigation: add golden-output integration tests before refactoring. Record current BM25+RRF output for a known index and assert after.

3. **SPI unification could break ServiceLoader.** Moving `dev.loqj.spi.*` into `dev.loqj.core.spi.*` requires updating `META-INF/services/` files. Mitigation: do this in a single commit, test `EngineRegistry` discovery.

4. **JavaFX dependency on CI/headless.** If tests or CI don't have JavaFX runtime, `FirstRunWizard` import in `Main.java` could fail. Mitigation: lazy-load wizard class or make JavaFX a runtime-only dep.

5. **Reranking latency.** When real rerankers are added (Phase 2), they add LLM round-trips per query. Mitigation: make reranking opt-in via config, `NoOpReranker` as default.

### Open Questions

1. **Should `dev.loqj.spi` (engine SPI) physically merge into `dev.loqj.core.spi`?** Or keep separate but document `core.spi` as primary? I lean toward physical merge (less confusion), but it's a bigger diff.

2. **Should PDFBox/POI stay or go?** They're 15+ MB of transitive deps. If PDF/DOCX parsing is Phase 2+, remove now and re-add later. If you want to keep the option, keep them but don't add dead code paths.

3. **Is LangChain4j useful here?** I looked at the codebase: LOQ-J has its own SPI, its own embeddings client, its own LLM client, its own retriever. LangChain4j would replace all of these. The tradeoff: you'd get a richer ecosystem (more model providers, built-in rerankers, document loaders) but lose control over the retrieval pipeline internals. **My recommendation: don't adopt LangChain4j in core.** If needed later, build a `langchain4j-adapter` package that wraps the LOQ-J pipeline as a LangChain4j retriever. Keep the core framework-neutral.

4. **When should Gradle submodules happen?** When you have a second consumer (MCP server, IDE plugin, or library JAR published to Maven). Not before. The overhead isn't justified for a single-app codebase.

5. **Should `Config` use a typed model instead of `Map<String, Object>`?** Yes, eventually. But it's a large refactor with wide blast radius. Defer to Phase 2 when the config surface stabilizes after pipeline changes.

### What to Validate Next

1. **Run the existing 22 tests and confirm green.** Before any changes.
2. **Profile a real indexing + retrieval cycle** on a medium codebase (~500 files). Identify actual bottlenecks (embedding latency? Lucene commit time? chunking?).
3. **Verify the `RetrieverEngine` SPI is truly orphaned.** Search for any reflection or ServiceLoader usage that might load `Bm25KnnRetriever`. (I found none, but confirm.)
4. **Assess whether `CachingLanguageModel` and `OllamaModels` in `core.llm` are used anywhere.** If orphaned, delete in Phase 0.
5. **Test KNN retrieval end-to-end with a real Ollama instance** to verify vector search quality before building pipeline around it.

---

## Plan Evaluation: My Opinion

Your plan is **well-structured and grounded**. Here's my honest assessment:

### What's strong about your plan
- **The Loqs suite separation is correct.** LOQ-J as knowledge engine, Loqs Core as orchestrator, Memory/Vision/Actions as separate concerns. This prevents LOQ-J from becoming a monolith.
- **"Don't chase buzzwords" is the right instinct.** RAG isn't dead. The problem is bad RAG. Your feature list (hybrid retrieval, reranking, better chunking, query improvement, context packing) is exactly what separates good RAG from naive RAG.
- **Phasing is correct.** Foundation before features. Pipeline before reranking. Local before server.
- **Keeping the core framework-neutral is wise.** LangChain4j/Spring AI as adapters, not foundations.

### Where I'd push back or adjust
- **Phase 0 and Phase 1 should partially overlap.** Don't wait for all cleanup to finish before starting the pipeline abstraction. The pipeline is the thing that makes cleanup payoff visible. Do: delete dead code (week 1), build pipeline skeleton (week 2), wire pipeline + close test gaps (week 3).
- **Don't over-engineer the parser registry in Phase 2.** A `Map<String, Parser>` keyed by extension is enough. ServiceLoader-based parser discovery is YAGNI unless you expect third-party parser plugins.
- **The "programmatic API surface" in Phase 2 should be Phase 1.5.** Even a simple `LoqjRetriever.query(path, question) -> List<Result>` facade makes the pipeline usable from tests and future consumers. Don't wait for MCP to justify a clean API.
- **Consider dropping JavaFX entirely.** The first-run wizard could be a CLI questionnaire (Picocli already supports it). JavaFX adds ~20MB of deps for a rarely-used feature on a CLI tool.

### Bottom line

The plan is actionable, correctly prioritized, and grounded in the actual code. The biggest risk is not the plan itself — it's execution discipline. The temptation will be to skip Phase 0 cleanup and jump to shiny pipeline work. Resist that. The dead code, duplicate implementations, and missing tests will bite you during every refactor if not addressed first.

**Recommended first commit from this plan:** Create a branch `feature/phase0-cleanup` from `v0.9.0-beta-dev`. Delete the 6 stub engine files, delete `RagMemoryMode`, fix the duplicate SQLite dep, and add 3-4 targeted tests. Merge. Then start `feature/retrieval-pipeline`.
