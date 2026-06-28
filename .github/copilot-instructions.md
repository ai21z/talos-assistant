# Talos - Copilot / AI Assistant Project Instructions

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

Talos is a **local-first CLI workspace assistant** and execution harness for
bounded local workspace work.

Repository identity:

- Product name: Talos
- Repository name: `talos-cli`
- GitHub repository: `ai21z/talos-cli`
- Public description: "Local-first CLI workspace assistant with retrieval,
  approval-gated file operations, traces, context handling, and
  verification-oriented outcomes."

Talos currently focuses on:

- workspace inspection through local tools
- local context retrieval and context packing
- approval-gated file operations
- bounded command execution through approved profiles
- local traces, prompt/debug evidence, and outcome records
- context handling across turns
- verification-oriented completion reporting

Talos is **not**:

- a foundation model
- a cloud-agent clone
- a swarm or multi-agent platform
- a background autonomous daemon
- a general browser/email/calendar automation product
- just a RAG CLI

Do not weaken explicit user control, approval gates, workspace boundaries,
traceability, or verification-oriented outcomes.

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

- `dev.talos.core.retrieval` - retrieval pipeline, stages, traces
- `dev.talos.core.rerank` - reranker interface and implementations
- `dev.talos.core.context` - context packing, token budgets
- `dev.talos.core.ingest` - parsing, chunking
- `dev.talos.core.index` - Lucene indexing
- `dev.talos.core.embed` - embeddings client
- `dev.talos.core.cache` - SQLite caching
- `dev.talos.core.llm` - LLM client abstraction
- `dev.talos.tools` - tool registry and local workspace tool implementations
- `dev.talos.api` - programmatic API seam (`TalosKnowledgeEngine`)
- `dev.talos.cli` - CLI commands and REPL

### Retrieval pipeline

`RagService.prepare()` routes through `RetrievalPipeline`:
BM25 → KNN → RRF Fusion → Rerank → Dedup

Stages are stateless (`StageOutput` record). Traces are captured per-stage.

---

## What NOT to do

- Do not rewrite the core around LangChain4j or Spring AI
- Do not merge broad long-term memory into Talos core without a scoped design
- Do not add MCP server logic until the local tool and retrieval seams are stable
- Do not perform broad package reshuffles without a concrete reason
- Do not delete legacy code before proving parity with new code
- Do not push CI/quality tooling changes into dev or main without review
