# T717 - Symbol Retrieval Migration And Extractor Coverage

Status: done
Priority: low
Created: 2026-06-07
Completed: 2026-06-07

## Evidence Summary

- Source: `work-cycle-docs/research/t708-t715-opus-review.md`
- Source: PR #287 Codex review comments, verified against local source on 2026-06-07
- Branch: `feature/t708-project-memory-analysis`
- HEAD at creation: `18b9c5b5cf5075f70850696d07438053766849ef`
- Current analyzed HEAD: `b73301fc7dd31b90ccaafbfafb81a502cd933d6f`
- Talos version: `0.9.9`

Expected behavior:

```text
The lightweight symbol extractor should avoid obvious phantom symbols from
code-like string literals and have direct tests for every language family it
claims to scan. Symbol sidecar migration should not silently leave structure-
first retrieval disabled after upgrading from a Lucene-only index.
```

Observed behavior:

```text
T715 made comment stripping quote-aware enough to avoid dropping symbols after
http://, //, or /* inside same-line string literals. The scanner still preserves
string interiors before regex extraction, so code-like strings can produce
phantom symbols. Template literal quote state is line-oriented, and direct tests
currently cover Java, JavaScript, and Python, but not every in-scope format.

PR #287 added two verified P2 review findings:

1. Symbol sidecar migration gap. `Indexer.index(...)` loads existing symbol
   sidecar data into `existingSymbolsByPath`, but when upgrading from an index
   with Lucene chunks and no `talos-symbols.json`, that map is empty. Unchanged
   files can hit `store.isUpToDate(...)` and return before
   `SymbolExtractor.extract(...)` populates `refreshedSymbolsByPath`. The later
   `writeMergedSymbolIndex(...)` therefore writes an empty sidecar for unchanged
   code files.
2. Package-private Java methods are skipped. `SymbolExtractor.JAVA_METHOD`
   requires at least one Java modifier before the return type, so declarations
   such as `String buildSetlist()` or `void helper()` are not indexed.
```

## Goal

```text
Improve symbol-retrieval reliability by handling Lucene-only index migration,
masking string interiors before regex matching, indexing package-private Java
methods, and adding direct language-family coverage.
```

## Non-Goals

- Deferred beyond the current T716 batch.
- No parser/tree-sitter dependency unless a later design ticket justifies it.
- No retrieval pipeline rewrite.
- No vector work.

## Architecture Metadata

Capability:

- Structure-first code retrieval / symbol extraction

Operation(s):

- index
- retrieve

Owning package/class:

- `dev.talos.core.index.Indexer`
- `dev.talos.core.index.SymbolExtractor`
- `dev.talos.core.index.SymbolIndexStore`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium retrieval-quality/migration risk
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: indexer and extractor unit tests
- Verification profile: none
- Repair profile: none

Outcome and trace:

- No expected trace shape change.

Refactor scope:

- Allowed: small scanner helper changes in `SymbolExtractor`.
- Forbidden: broad parser dependency without a new design review.

## PR #287 Review Findings To Cover

### F1 - Lucene-only index migration can write an empty symbol sidecar

Evidence:

- `Indexer.index(...)` builds `existingSymbolsByPath` from
  `SymbolIndexStore.load(indexDir)`, which is empty when `talos-symbols.json` is
  missing.
- Unchanged files return at `store.isUpToDate(rel, currentHash)` before parsing
  or symbol extraction.
- `writeMergedSymbolIndex(...)` falls back to `existingSymbolsByPath` for files
  not present in `refreshedSymbolsByPath`, so a missing sidecar plus unchanged
  Lucene chunks can produce an empty sidecar.

Fix direction:

- Detect missing/corrupt sidecar at index start and either force symbol refresh
  for all current indexable files or parse unchanged files for symbols while
  preserving Lucene chunk skip behavior.
- Do not force vector/chunk rewrites merely to populate symbols unless needed.

Regression:

- Build a Lucene index with code symbols, delete `talos-symbols.json`, run
  normal non-force `index(...)`/`reindex(...)`, and assert public code symbols
  are restored without requiring a forced full reindex.

### F2 - Package-private Java methods are not indexed

Evidence:

- `SymbolExtractor.JAVA_METHOD` currently requires at least one modifier group.
- Package-private declarations such as `String buildSetlist()` and
  `void helper()` do not match that pattern.
- Current Java extractor tests assert a public method but do not assert the
  interface/package-private `void saveConcert();` fixture is extracted.

Fix direction:

- Make the Java modifier prefix optional while keeping control-flow guards.
- Add constructor handling explicitly: either exclude constructors from method
  symbols or represent them deliberately, but do not accidentally classify
  constructors as ordinary methods.

Regression:

- Add tests for package-private class methods and package-private interface
  methods.
- Add a constructor fixture to prove the chosen behavior.

## Acceptance Criteria

- A normal non-force reindex restores `talos-symbols.json` when the Lucene index
  exists but the symbol sidecar is missing.
- The migration path does not persist protected-path symbols.
- Code-like string content such as `"export function fake() {}"` does not create a phantom symbol hit.
- Existing same-line string/comment-token fixes from T715 remain green.
- Package-private Java methods are extracted as method symbols.
- Constructor declarations are handled intentionally and covered by tests.
- Direct tests cover at least TypeScript plus one JVM-adjacent format currently routed through Java-like extraction.
- Any remaining multiline template-literal limitation is documented in code or ticket notes.

## Suggested Focused Tests

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.IndexerSymbolIndexSidecarTest" --tests "dev.talos.core.index.SymbolExtractorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --no-daemon
```

## Known Risks

- Over-masking strings could hide legitimate same-line declarations following a string literal if implemented incorrectly.
- Language-perfect extraction is out of scope for this lightweight scanner.

## Completion Evidence

- `Indexer` now treats missing/corrupt symbol sidecars as a symbol-refresh
  condition for unchanged indexable files without forcing Lucene chunk rewrites.
- `SymbolExtractor` masks string-literal interiors before regex matching while
  preserving original stripped lines for symbol signatures.
- Package-private Java methods are extracted; constructors are covered as a
  deliberate non-method case.
- Direct tests now cover TypeScript and Kotlin class extraction in addition to
  Java, JavaScript, and Python.
- Multiline template-literal state remains a documented limitation of the
  lightweight line-oriented scanner.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.IndexerSymbolIndexSidecarTest" --tests "dev.talos.core.index.SymbolExtractorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --no-daemon
```
