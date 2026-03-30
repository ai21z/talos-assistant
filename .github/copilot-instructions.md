# LOQ-J — Copilot / AI Assistant Project Instructions

These instructions are read automatically by GitHub Copilot Chat and should
be treated as persistent project rules for any AI assistant working in this
repository.

---

## Branch Model

### Source of truth

- **`v0.9.0-beta-dev`** is the active development branch.
- **`main`** is the stable release branch. Do not target it directly.
- All feature work branches off `v0.9.0-beta-dev` and merges back into it.

### Branch rules

- Always create a new feature branch from `v0.9.0-beta-dev`.
- Never commit directly to `v0.9.0-beta-dev` or `main`.
- Never push to `main` unless performing a deliberate release merge.

### Infrastructure / tooling isolation

**CI workflows, quality tooling, and build-infrastructure changes must NOT
be merged into `v0.9.0-beta-dev` or `main` without explicit approval.**

These include:
- `.github/workflows/` files
- JaCoCo / Sonar / Qodana / Snyk / CodeQL configuration
- Build plugin additions that affect CI behavior
- Quality gate threshold changes

Such changes must live on their own branch (e.g., `feature/code-quality-stack`)
and be reviewed as a standalone PR before merging into `v0.9.0-beta-dev`.

**Reason:** Infrastructure changes affect every downstream branch and CI run.
They must be intentional, not accidental side effects of a feature branch.

### Current long-lived branches

| Branch | Purpose | Merge target |
|---|---|---|
| `v0.9.0-beta-dev` | Active development | `main` (on release) |
| `feature/retrieval-pipeline` | Retrieval + context assembly modernization | `v0.9.0-beta-dev` |
| `feature/code-quality-stack` | CI/quality tooling (JaCoCo, Sonar, Qodana, CodeQL, Snyk) | `v0.9.0-beta-dev` (after review) |

---

## Project Identity

LOQ-J is a **local-first Java knowledge and context engine** for the Loqs suite.

It is responsible for:
- ingestion, parsing, chunking
- indexing (Lucene-backed)
- retrieval (hybrid: BM25 + vector + metadata)
- reranking
- provenance and retrieval traces
- context packing / evidence assembly

It is **not** responsible for:
- agent orchestration, planning, or routing (→ Loqs Core)
- durable assistant/task/user memory (→ Loqs Memory)
- screenshots, PDFs-as-images, UI understanding (→ Loqs Vision)
- browser, email, files, calendar automation (→ Loqs Actions)
- multi-agent coordination (→ Loqs MAS)

Do not introduce agent-platform concerns into LOQ-J core.

---

## Coding Conventions

- Java 21, Gradle 8.14, Kotlin DSL (`build.gradle.kts`)
- JUnit 5 for tests
- Framework-neutral core; frameworks are adapters, not the architecture
- Local-first, privacy-first
- Keep diffs tight; avoid speculative abstractions
- Preserve existing behavior before deleting legacy code

---

## Architecture Notes

### Key packages

- `dev.loqj.core.retrieval` — retrieval pipeline, stages, traces
- `dev.loqj.core.rerank` — reranker interface and implementations
- `dev.loqj.core.context` — context packing, token budgets
- `dev.loqj.core.ingest` — parsing, chunking
- `dev.loqj.core.index` — Lucene indexing
- `dev.loqj.core.embed` — embeddings client
- `dev.loqj.core.cache` — SQLite caching
- `dev.loqj.core.llm` — LLM client abstraction
- `dev.loqj.tools` — future tool/MCP seam
- `dev.loqj.api` — programmatic API seam (`LoqjKnowledgeEngine`)
- `dev.loqj.cli` — CLI commands and REPL

### Retrieval pipeline

`RagService.prepare()` routes through `RetrievalPipeline`:
BM25 → KNN → RRF Fusion → Rerank → Dedup

Stages are stateless (`StageOutput` record). Traces are captured per-stage.

---

## What NOT to do

- Do not rewrite the core around LangChain4j or Spring AI
- Do not merge long-term memory into LOQ-J core
- Do not add MCP server logic until the retrieval seam is stable
- Do not perform broad package reshuffles without a concrete reason
- Do not delete legacy code before proving parity with new code
- Do not push CI/quality tooling changes into dev or main without review

