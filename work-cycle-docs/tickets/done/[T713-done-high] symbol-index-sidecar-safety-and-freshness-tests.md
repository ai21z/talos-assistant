# [T713-done-high] Symbol Index Sidecar Safety And Freshness Tests

Status: done
Priority: high

## Evidence Summary

- Source: static code review of T708-T712 working tree and `work-cycle-docs/research/t708-t712-opus-review.md`
- Date: 2026-06-07
- Talos version / commit: `talosVersion=0.9.9`, branch `codex/t708-project-memory-analysis`, HEAD `18b9c5b5cf5075f70850696d07438053766849ef`
- Model/backend: not applicable; deterministic code/test follow-up
- Workspace fixture: temp workspaces under JUnit
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime failure transcript; code review found direct sidecar/freshness coverage gaps around the T710 symbol index
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: focused and full checks passed on 2026-06-07

Closeout evidence, 2026-06-07:

- Added direct sidecar tests for protected-path exclusion and deleted-file freshness.
- Added malformed sidecar fail-closed coverage in `SymbolIndexStoreTest`.
- Added `RagService` corrupt-sidecar coverage proving malformed symbol sidecars do not return stale symbol hits.
- Commands passed:
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.runtime.SessionMemoryTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.*" --tests "dev.talos.runtime.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.cli.repl.slash.*" --no-daemon`
  - `git diff --check`
  - `.\gradlew.bat check --no-daemon`

Redacted prompt sequence:

```text
Review T708-T712 implementation, especially T710 symbol retrieval, against code and sources.
```

Expected behavior:

```text
Symbol sidecar data that feeds model context must have deterministic tests for:
- protected/private path exclusion before sidecar persistence;
- stale/deleted file removal on reindex;
- malformed sidecar recovery without model-visible stale evidence.
```

Observed behavior:

```text
T710 has meaningful retrieval-level coverage. In particular,
RagServiceSymbolRetrievalTest.protectedFileSymbolsAreExcludedFromIndirectRetrieval
creates protected/SecretService.java and asserts no SecretService symbol is returned
from RagService.prepare(...).

The remaining gap is narrower: tests do not directly inspect talos-symbols.json after
indexing, and do not prove deleted-file removal or corrupt-sidecar recovery.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `TRACE_REDACTION`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
Symbol evidence is model-visible context. A sidecar privacy or freshness regression
would not mutate files, but it could put protected or stale symbol signatures into
retrieval context. Current tests cover the retrieval outcome path, but direct sidecar
artifact and freshness behavior deserve deterministic regression coverage before
treating T710 as release-grade.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Fix RAG prompt wording.
```

Architectural hypothesis:

```text
The symbol sidecar is a local context artifact and model-context evidence source.
Its invariants belong at the indexing/storage boundary, not only at RagService
display/query time. Tests should assert the persisted sidecar and rebuild behavior
directly.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/index/Indexer.java`
- `src/main/java/dev/talos/core/index/SymbolIndexStore.java`
- `src/main/java/dev/talos/core/rag/RagService.java`
- `src/test/java/dev/talos/core/index/*`
- `src/test/java/dev/talos/core/rag/RagServiceSymbolRetrievalTest.java`
- `work-cycle-docs/tickets/done/[T710-done-high] structure-first-code-retrieval-and-symbol-index.md`

Why a one-off patch is insufficient:

```text
This is a recurring trust invariant for any future structure-first retrieval lane:
sidecar artifacts must respect privacy filters, freshness, and corrupt artifact
recovery independently of model behavior.
```

## Goal

```text
Prove, with direct sidecar tests, that symbol index persistence excludes protected
paths, removes deleted file symbols, and recovers safely from malformed symbol
sidecar data.
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or verification policy.
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.
- No vector database work.
- No broad RAG rewrite.
- No semantic code parser replacement in this ticket.

## Implementation Notes

```text
Add tests before changing behavior. Prefer a focused indexer integration test that
uses a temp workspace, invokes the existing indexing path, then reads
SymbolIndexStore.load(indexDir) directly. Preserve the existing retrieval-level
protected-symbol test because it proves model-visible prepared context remains clean.
```

## Architecture Metadata

Capability:

- Structure-first code retrieval / symbol evidence

Operation(s):

- index
- retrieve

Owning package/class:

- `dev.talos.core.index.Indexer`
- `dev.talos.core.index.SymbolIndexStore`
- `dev.talos.core.rag.RagService`

New or changed tools:

- None expected

Risk, approval, and protected paths:

- Risk level: privacy/context risk, no mutation risk
- Approval behavior: unchanged
- Protected path behavior: protected symbols must not be persisted or returned through indirect retrieval

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: direct sidecar artifact evidence and retrieval prepared-context evidence
- Verification profile: deterministic unit/integration tests
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: do not claim symbol sidecar privacy until artifact-level tests pass
- Trace/debug fields: existing retrieval trace should remain unchanged unless tests reveal a trace gap

Refactor scope:

- Allow small test seams only if necessary to locate the index directory.
- Do not rewrite indexing or RAG ranking unless a RED test proves a defect.

## Acceptance Criteria

- A direct sidecar test creates `protected/SecretService.java` plus a public code file, indexes the workspace, loads `talos-symbols.json` through `SymbolIndexStore.load(...)`, and proves the protected symbol is absent while the public symbol is present.
- A stale/deleted-file test indexes a code file, deletes it, reindexes, and proves its symbols are removed from the sidecar.
- A corrupt-sidecar test writes malformed symbol sidecar JSON and proves `SymbolIndexStore.load(...)` fails closed without throwing or returning stale data.
- If the normal RAG preparation path rebuilds or ignores a corrupt sidecar, that behavior is covered by a test.
- Existing `RagServiceSymbolRetrievalTest.protectedFileSymbolsAreExcludedFromIndirectRetrieval` remains green or is strengthened, not weakened.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `SymbolIndexStoreTest` corrupt-sidecar load behavior.
- Integration/executor test: new or existing indexer test proving protected exclusion and deleted-file freshness at sidecar level.
- JSON e2e scenario: not required.
- Trace assertion: not required.

Manual/TalosBench rerun:

- Prompt family: not required for this ticket.
- Workspace fixture: temp workspace with protected and public code files.
- Expected trace: not applicable.
- Expected outcome: sidecar and retrieval context exclude protected symbols.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop unless the ticket explicitly declares a candidate.
- Do not bump version unless this is candidate closeout.
- Do not update `CHANGELOG.md` unless this is candidate closeout.
- Convert any discovered sidecar behavior defect into a focused deterministic regression before closeout.

## Known Risks

- The existing retrieval-level protected-symbol test already covers the final prepared-context path. Do not duplicate it and mistake duplication for new coverage.
- Direct sidecar tests must use the same index directory policy as production code, not an artificial store-only fixture that bypasses `Indexer`.

## Known Follow-Ups

- If sidecar tests expose a real privacy or freshness defect, split the code fix into a separate implementation commit before closing this ticket.
