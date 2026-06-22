# T847 Retrieval Evidence And Gold-Context Harness

Status: implemented awaiting review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation state: implemented awaiting review; commit SHA to be recorded at closeout if accepted

## Decision

T847 starts retrieval/workspace-intelligence work with measurement, not ranking
changes. Talos already has Lucene BM25, optional vectors, RRF, reranking,
deduplication, symbol sidecar infrastructure, and retrieval trace plumbing. The
missing pre-beta asset is a repeatable way to ask whether a retrieval run placed
the right files, symbols, line ranges, related tests, and privacy negatives in
context.

T847 does not change retrieval ranking, vector behavior, graph expansion, repo
maps, memory, RAG indexing, or prompt assembly.

## Harness Added

Test owner:

```text
src/test/java/dev/talos/core/retrieval/RetrievalGoldContextHarnessTest.java
```

The harness defines 20 gold-context tasks against a deterministic synthetic
Talos-like corpus. Each task can record:

- expected files;
- expected symbols;
- expected line ranges;
- related tests;
- BM25-only lane contribution;
- hybrid/vector lane contribution when synthetic vectors are present;
- forbidden paths for protected-path negative checks;
- a private-mode negative case where retrieval is skipped.

The first pass is intentionally test-side only. It uses `LuceneStore` and the
existing retrieval pipeline stages, but does not alter production code.

## Metric Surface

The harness computes:

- file recall;
- file precision;
- MRR;
- nDCG;
- junk context rate;
- missing-core-evidence rate;
- line-range hit count;
- BM25 and KNN lane contribution counts;
- protected-path negative status;
- private-mode negative status.

The deterministic gate currently requires the BM25-only and hybrid synthetic
runs to compute valid metric values in `[0, 1]`, cover exactly 20 tasks, include
18 retrieval tasks plus 2 negative tasks, and retain at least a modest
synthetic fixture recall threshold. The threshold is deliberately not a public
quality claim.

## Current Scope

The harness includes:

- **20 gold-context tasks** total;
- **18 retrieval tasks**;
- **2 privacy/trust negative tasks**;
- one protected-path negative;
- one private-mode negative;
- BM25-only execution;
- hybrid execution with synthetic vectors;
- expected files, symbols, line ranges, and related tests.

The tracked report is leak-reviewed by construction: all corpus entries are
synthetic Talos-like class names, docs names, and policy descriptions. It does
not contain local secrets, provider bodies, real prompts, raw private files, or
site content.

## Non-Claims

- T847 does not prove Talos has strong codebase intelligence.
- T847 does not validate live embedding endpoints.
- T847 does not validate a real user repository.
- T847 does not compare model answer quality.
- T847 does not authorize vector DB replacement, graph retrieval, repo maps, or
  memory work.
- T847 does not change production retrieval behavior.

## Follow-Up Selection Rule

The first production retrieval/workspace-intelligence ticket after T847 should
come from measured failure modes in the harness or live beta evidence, not from
generic vector hype. Candidate follow-up themes remain:

- retrieval trace and context-explain surface;
- repo map builder;
- symbol index hardening;
- file-first ranking and context assembler;
- bounded graph expansion only if measured evidence shows it helps.

## Verification

Required gate for this implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.core.retrieval.RetrievalGoldContextHarnessTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.retrieval.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
git status --short -- . ':!site'
```

T847 should stay open until this implementation is reviewed.
