# T716 - Symbol Sidecar Recovery And Evidence Wording

Status: done
Priority: medium
Created: 2026-06-07
Completed: 2026-06-07

## Evidence Summary

- Source: `work-cycle-docs/research/t708-t715-independent review-review.md` plus static review of current working tree
- Branch: `codex/t708-project-memory-analysis`
- HEAD at creation: `18b9c5b5cf5075f70850696d07438053766849ef`
- Talos version: `0.9.9`

Expected behavior:

```text
Symbol sidecar health should be visible to the retrieval pipeline. A corrupt
talos-symbols.json must not silently disable structure-first retrieval, and
symbol signature snippets must not be worded as full exact code evidence.
```

Observed behavior:

```text
RagService.ensureIndexExists(...) treats any existing talos-symbols.json as
healthy without parsing it. SymbolIndexStore.load(...) then fails closed on a
malformed sidecar by returning empty hits. This avoids stale/private leakage, but
silently drops the symbol lane. User/model-facing wording also says "Exact
symbol evidence" / "exact code evidence" even though the payload is a signature
line, not full file inspection.
```

## Goal

```text
Recover or surface corrupt symbol-sidecar state, and make symbol evidence wording
truthful as "symbol signature match" rather than "exact code evidence."
```

## Non-Goals

- No vector memory.
- No parser dependency.
- No broad RAG rewrite.
- No browser/live audit.
- No public CLI command change.
- No trace schema key change for `memoryRetentionStatus`.

## Architecture Metadata

Capability:

- Structure-first code retrieval / symbol evidence

Operation(s):

- index
- retrieve
- trace

Owning package/class:

- `dev.talos.core.index.SymbolIndexStore`
- `dev.talos.core.rag.RagService`
- `dev.talos.tools.impl.RetrieveTool`
- `dev.talos.runtime.trace.PromptAuditSnapshot`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium reliability/auditability, not privacy P1
- Approval behavior: unchanged
- Protected path behavior: corrupt/protected symbol data must never become model-visible

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: deterministic index/retrieval tests and prompt/debug rendering tests
- Verification profile: none
- Repair profile: none

Outcome and trace:

- Retrieval trace/debug should reveal corrupt sidecar recovery or limitation.
- Human-readable evidence labels must not imply full file inspection.

Refactor scope:

- Allowed: small internal result type for symbol-sidecar health.
- Forbidden: replacing the retrieval/index pipeline.

## Acceptance Criteria

- `SymbolIndexStore` exposes a detailed load status: `MISSING`, `LOADED`, `CORRUPT`, while legacy `load(...)` and `query(...)` remain fail-closed compatible wrappers.
- `RagService.ensureIndexExists(...)` rebuilds when `talos-symbols.json` exists but is corrupt.
- If a corrupt sidecar is encountered during retrieval after ensure/rebuild, retrieval fails closed and records a trace/debug limitation rather than silently dropping symbol evidence.
- Model-context snippets use `[Symbol signature match - not full file contents]`.
- `talos.retrieve` output uses `Symbol signature matches (not full file contents):`.
- Retrieval trace note says `symbol signature match`, not `exact symbol match`.
- Human-rendered memory-retention labels state that counts are cumulative for the session, while the audit field name `memoryRetentionStatus` remains unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- `SymbolIndexStoreTest`: malformed sidecar returns `CORRUPT` through the detailed load API while legacy `load(...)` returns empty.
- `RagServiceSymbolRetrievalTest`: corrupt symbol sidecar is rebuilt and returns expected public symbol hits.
- `RagServiceSymbolRetrievalTest`: symbol evidence snippet wording is "Symbol signature match - not full file contents".
- `RetrieveToolTest`: retrieve output wording uses "Symbol signature matches (not full file contents)".
- Prompt audit/slash/prompt-debug tests: rendered memory retention label says cumulative while the field remains `memoryRetentionStatus`.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.cli.repl.slash.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

Observed verification:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.SymbolIndexStoreTest" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --tests "dev.talos.cli.prompt.PromptDebugInspectorContextLedgerTest" --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
# BUILD SUCCESSFUL

.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.cli.repl.slash.*" --no-daemon
# BUILD SUCCESSFUL

.\gradlew.bat check --no-daemon
# BUILD SUCCESSFUL

git diff --check
# exit 0; line-ending warnings only
```

## Known Risks

- Rebuild-on-corrupt should not loop indefinitely if indexing fails.
- Trace limitation wording must remain redaction-safe.
