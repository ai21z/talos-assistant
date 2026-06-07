# T710 - Structure-First Code Retrieval And Symbol Index

Status: done
Priority: high
Created: 2026-06-06
Completed: 2026-06-07

## Evidence Summary

- Source: static architecture/code review plus research synthesis
- Date: 2026-06-06
- Talos version / commit: `0.9.9` / `dd67d6864e3ccb084f1efef532930e0824ef3c15`
- Evidence:
  - `work-cycle-docs/research/context-retrieval-memory-best-techniques-from-reference-systems.md`
  - `src/main/java/dev/talos/core/rag/RagService.java`
  - `src/main/java/dev/talos/core/index/`
  - `src/main/java/dev/talos/core/retrieval/`

Expected behavior:

```text
For code work, Talos should prefer structure, filenames, symbols, and exact
keyword evidence before semantic/vector recall. Vectors may remain an optional
recall signal, not the primary code-retrieval spine.
```

Observed behavior:

```text
Talos has a hybrid RAG pipeline, but the research doc shows reference coding
agents primarily use structure search, ripgrep/glob/read flows, and symbol-level
navigation. Talos does not yet have a dedicated symbol index or task-routed
retrieval policy that demotes vectors for code tasks.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `TOOL_SURFACE`
- `VERIFICATION`

Blocker level:

- future milestone

Why this level:

```text
This improves developer-task competence and context economy, but should follow
T707 and should not distract from static-web repair convergence.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Upgrade embeddings.
```

Architectural hypothesis:

```text
Talos needs task-routed retrieval. Code tasks should start with structure,
filenames, symbols, and exact search; vector retrieval should be optional and
secondary. A symbol index is higher leverage than a larger embedding model.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/index/`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/main/java/dev/talos/core/retrieval/`
- `src/main/java/dev/talos/runtime/policy/EvidenceObligationPolicy.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`

Why a one-off patch is insufficient:

```text
Changing one RAG weight does not create structure-first retrieval. The system
needs task-aware retrieval routing and symbol evidence that can be cited and
audited.
```

## Goal

```text
Design and implement a structure-first retrieval lane for code tasks, including
symbol indexing and task-routed retrieval behavior, without making vector RAG the
primary strategy.
```

## Non-Goals

- No embedding-model swap as the main solution.
- No vector database dependency.
- No broad rewrite of RAG.
- No hidden autonomous repo crawling outside existing index policy.

## Implementation Notes

Initial direction:

- Add a small symbol index for common code/project files, starting with stable
  language-neutral identifiers where possible.
- Route code/debug/refactor questions through structure and keyword evidence
  before semantic retrieval.
- Keep `rg`/grep/read-style evidence visible in trace/prompt-debug.
- Use vector recall only as a secondary signal when exact/structure evidence is
  insufficient.
- Preserve private/protected-path filters.

Implementation refinement, 2026-06-07:

- Implement in slices:
  1. deterministic symbol extraction and persisted symbol-hit evidence;
  2. symbol-first retrieval evidence in `RagService` / `talos.retrieve`;
  3. trace/debug visibility for retrieval route and evidence type.
- Reuse the existing `Indexer` walk, include/exclude config, protected-path
  filters, and policy metadata. Do not add a second raw filesystem crawler.
- Keep vectors as an optional secondary recall signal. The current shipped YAML
  enables vectors, while `Config.ensureDefaults()` only defaults them to false
  when the key is absent; this ticket is therefore about route/evidence order,
  not a vector-default toggle.
- Avoid a broad parser dependency in this slice. Start with conservative,
  deterministic symbol extraction and auditable line/kind evidence; Tree-sitter
  or LSP-backed indexing can be a later ticket if the regex extractor proves too
  weak.
- Completed implementation adds a persisted symbol sidecar, retrieval trace
  route/evidence rows, `talos.retrieve` symbol-hit rendering, and a direct
  `RagService.ask` bridge that pins exact symbol evidence into model context
  before ordinary snippets.

## Architecture Metadata

Capability:

- Code retrieval / workspace grounding

Operation(s):

- read, retrieve, index

Owning package/class:

- `dev.talos.core.index`
- `dev.talos.core.retrieval`
- `dev.talos.core.rag.RagService`

New or changed tools:

- none initially

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: read-only retrieval follows existing policy
- Protected path behavior: protected/private files must remain excluded from
  indirect retrieval unless policy explicitly allows

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: retrieved code facts must cite file/path/symbol evidence
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: answers must distinguish exact symbol evidence from
  semantic recall
- Trace/debug fields: retrieval route, symbol hits, exact hits, semantic hits

Refactor scope:

- Allowed: add retrieval route/profile classes
- Forbidden: replacing existing Lucene/RAG pipeline wholesale

## Acceptance Criteria

- Code-task retrieval uses structure/symbol/keyword evidence before vector recall.
- Symbol index supports at least one deterministic repo fixture and produces
  auditable path/symbol hits.
- Retrieval trace identifies route and evidence type.
- Protected/private filters apply to symbol and keyword retrieval.
- Tests prove exact symbol queries do not require vectors.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: symbol index extracts known identifiers from a fixture.
- Retrieval test: exact symbol query returns symbol/path evidence without vector
  dependency.
- Privacy test: protected file symbols are excluded from indirect retrieval.
- Trace assertion: retrieval route is visible.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.retrieval.*" --tests "dev.talos.core.rag.*" --no-daemon
.\gradlew.bat check --no-daemon
```

Completed evidence, 2026-06-07:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.SymbolExtractorTest" --tests "dev.talos.core.index.SymbolIndexStoreTest" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.retrieval.*" --tests "dev.talos.core.rag.*" --tests "dev.talos.tools.impl.RetrieveToolTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

Result: all listed Gradle commands passed; `git diff --check` passed.

## Work-Test Cycle Notes

- Start with design and a minimal symbol fixture.
- Do not add a vector DB.
- Keep vectors optional and secondary.

## Known Risks

- Over-indexing could leak protected content through indirect search.
- Language-specific parsing can sprawl; start with simple, testable symbol extraction.

## Known Follow-Ups

- Task-specific retrieval routing for document extraction and static-web tasks.
