# [T847-open-high] Retrieval Evidence And Gold-Context Harness

Status: open
Priority: high
Type: evaluation-harness
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Measure Talos workspace-intelligence quality directly before changing retrieval
ranking, repo maps, graph expansion, or memory behavior.

The current direction is clear: Talos is not behind on vector database plumbing.
It needs measured evidence that retrieval assembles the right files, symbols,
ranges, related tests, and reasons.

## Scope

Build a small gold-context benchmark for retrieval quality:

- 20 to 30 initial tasks;
- expected files;
- expected symbols;
- expected line ranges when meaningful;
- expected related tests;
- BM25/vector/symbol lane contribution;
- token budget used by context;
- junk context;
- missing-core-evidence rate;
- privacy cases for protected-path exclusion and private-mode RAG disablement.

## Metrics

The first harness should report:

- file recall;
- file precision;
- MRR or nDCG for expected evidence;
- junk-context rate;
- missing-core-evidence rate;
- whether BM25, vector, symbol, or future graph evidence mattered.

## Non-Goals

- No ranking behavior change in the characterization phase.
- No vector database replacement.
- No graph expansion until the harness shows where it helps.
- No public claim that Talos has strong codebase intelligence until the metric
  results support it.

## Expected Follow-Up Track

The original retrieval follow-up numbers are intentionally released for the
T842 beta-correctness findings. After T847 is accepted, retrieval follow-ups
should be renumbered after the current beta-correctness ticket sequence.

Expected retrieval follow-up themes remain:

- retrieval trace and context-explain surface;
- repo map builder;
- symbol index hardening;
- file-first ranking and context assembler;
- bounded graph expansion or memory candidates only if the harness shows value.

## Acceptance Criteria

- The harness can run without a live cloud service.
- It can run BM25-only and hybrid configurations separately when embeddings
  are available.
- It records protected-path and private-mode negative cases.
- Results are written as local/tracked evidence only after leak review.
- The first implementation ticket after T847 is selected from measured failure
  modes, not from vector hype.

## Implementation Pass

Status: implemented awaiting review.

Added:

- `src/test/java/dev/talos/core/retrieval/RetrievalGoldContextHarnessTest.java`
- `work-cycle-docs/reports/t847-retrieval-evidence-and-gold-context-harness.md`

Scope:

- 20 deterministic gold-context tasks.
- 18 retrieval tasks and 2 privacy/trust negative tasks.
- Expected files, expected symbols, expected line ranges, and related tests.
- BM25-only run over `LuceneStore` with no vectors.
- Hybrid synthetic-vector run over `LuceneStore` with deterministic vectors.
- Metrics for file recall, file precision, MRR, nDCG, junk context,
  missing-core-evidence, line-range hits, and lane contributions.

No production retrieval behavior changed. T847 remains open pending review.
