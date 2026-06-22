# [T847-done-high] Retrieval Evidence And Gold-Context Harness

Status: done
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

Pass status: implemented and reviewed (accepted at closeout).

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

No production retrieval behavior changed.

Closeout (Opus independent review, 2026-06-21):

- Implementation commit `0a5cc8c6005f88952af9d94b7713d9550182a682`. Scope verified
  test/report/docs only (no `src/main`, no retrieval/vector/RAG behavior change, no `site/`).
- I read the harness and confirmed it is NOT hollow or circular: it runs the real
  `RetrievalPipeline` (Bm25 + Knn + RRF + reranker + dedup over `LuceneStore`) and
  compares actual retrieved paths against INDEPENDENT gold expectations defined per
  task over a 24-document synthetic Talos-like corpus. Recall/precision/MRR/nDCG/
  junk-context/missing-core-evidence are real formulas over real retrieval output;
  the 0.60 recall floor is a measured baseline, not a rigged pass.
- The two privacy negatives are genuine: protected-path exclusion asserts forbidden
  paths are absent from results; private-mode asserts retrieval is skipped.
- `RetrievalGoldContextHarnessTest` 4/0/0 re-run by me. Report is honest: synthetic
  corpus, explicit Non-Claims (does not prove strong codebase intelligence, does not
  validate live embeddings or a real repo), recall threshold "not a public quality claim".
- Accepted as the measurement-first baseline. The next retrieval ticket must come from
  measured harness gaps or live beta evidence, not vector hype.
