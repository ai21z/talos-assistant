# [T352-done-high] Remaining Policy Boundary Ownership Decision

Status: done
Priority: high
Date: 2026-05-22
Branch: `T352`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T351-done-medium] move-grep-protected-content-safety-adapters`

## Evidence Summary

- Source: post-T351 architecture ratchet continuation after PR #16 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-22.
- Base branch: `origin/v0.9.0-beta-dev` at
  `2c50d8731feb5cc0ad6fc78eff8239b5bef69b52`.
- Beta push CI: run `#44`, `Beta Dev CI`, push event for `2c50d873`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T352`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: decision ticket only; no production code changed.
- Verification status: pending at ticket creation time.

## Problem

The early architecture-ratchet tickets removed cheap ownership lies:

- sink-safe logging moved to `dev.talos.safety`;
- pure protected-content sanitization moved to `ProtectedContentSanitizer`;
- direct workspace protected-path classification moved to
  `ProtectedWorkspacePaths`;
- `RetrieveTool` and `GrepTool` stopped importing runtime
  `ProtectedContentPolicy` for direct path/sanitizer work.

After T351, the remaining baseline is no longer dominated by cheap
sink-safety adapters. It contains mixed policy and contract boundaries:

- private-mode config facts versus runtime approved-read scope;
- private-document extraction facts versus model-handoff/artifact/RAG
  decisions;
- RAG retrieval results versus runtime context ledger records;
- index metadata policy versions versus runtime facade versions;
- tool implementations versus runtime command/workspace execution contracts;
- runtime orchestration versus CLI session/result/memory contracts;
- SPI purity.

Continuing as if each baseline row were an equal burn-down unit would produce
architecture theater. The correct next work is to decide ownership splits from
source evidence, then implement one split at a time.

## Current Baseline

After T351, `config/architecture-boundary-baseline.txt` has `43` entries:

- `core-no-runtime`: `11`
- `runtime-core-no-cli`: `15`
- `spi-no-upper-layers`: `4`
- `tools-no-runtime`: `13`

Remaining policy-specific entries:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

## Source Findings

### `GrepTool -> ProtectedReadScopePolicy`

`GrepTool` imports `ProtectedReadScopePolicy` only to ask whether private mode
is active:

- `execute(...)` passes `privateMode` into normal file search;
- `searchExtractedFile(...)` passes `privateMode` into extracted-document
  search-line rendering.

It does not need approved protected-read default scope, send-to-model override,
raw artifact persistence, approval wording, or `/privacy` mutation behavior.

The correct owner for this dependency is a lower-level read-only privacy config
facts component, not runtime approval-scope policy.

### `RagService -> ProtectedReadScopePolicy`

`RagService` uses two facts:

- `privateMode(cfg)`;
- `ragEnabledInPrivateMode(cfg)`.

Those are read-only config facts and could move below runtime. However,
`RagService` also imports runtime context ledger contracts and
`ProtectedContentPolicy`, so changing it in the same ticket would mix the
privacy-config split with the RAG/runtime-context split.

Do not use `RagService` as the first adopter for the privacy-config split.

### `DocumentExtractionService -> PrivateDocumentPolicy`

`DocumentExtractionService` extracts and sanitizes text, then asks
`PrivateDocumentPolicy.modelHandoffAllowed(...)` when constructing
`DocumentExtractionResult`.

That is mixed ownership:

- extraction status, adapter warnings, provenance, and safe text are core
  extraction facts;
- model handoff is runtime/tool-context policy.

Moving `PrivateDocumentPolicy` downward would be wrong because it still decides
model handoff, raw artifact persistence, RAG indexing, and user-facing decision
reasons.

The eventual fix is a contract split: core extraction should return extracted
facts, and runtime/tool adapters should attach model-handoff and persistence
decisions.

### `ReadFileTool -> PrivateDocumentPolicy`

`ReadFileTool` imports `PrivateDocumentPolicy` only when formatting
`ToolContentMetadata` for extracted documents:

- private document content flag;
- raw artifact persistence allowed;
- RAG index allowed;
- decision reason.

This is closer to runtime/tool handoff policy than core extraction. It should
not be solved by moving `PrivateDocumentPolicy` wholesale. The next design
should introduce a small decision/value object that can be computed by runtime
policy and consumed by tools without tools importing runtime policy.

### `Indexer -> ProtectedContentPolicy`

`Indexer` uses `ProtectedContentPolicy` for:

- `POLICY_VERSION` in index freshness metadata;
- direct protected-path exclusion before indexing.

The direct path check can use `ProtectedWorkspacePaths`, but the version is
more delicate. The current `privacyPolicyVersion` metadata is tied to a mixed
runtime facade. A correct split needs named lower-level policy versions:

- direct protected-path classification version;
- content sanitizer version if index text redaction changes can affect stored
  chunks;
- document extraction policy version, already present;
- privacy config hash, already present.

Do not change index metadata casually. An incorrect version split can either
force unnecessary reindexing or, worse, fail to rebuild stale unsafe indexes.

### `Indexer -> PrivateDocumentPolicy`

`Indexer` calls `PrivateDocumentPolicy.ragIndexAllowed(...)` and
`decisionReason(...)` when indexing extracted documents.

This is RAG privacy policy, not extraction. It is also not pure runtime
approved-read scope. The correct future shape is a core/RAG-visible privacy
indexing decision contract or a runtime-computed policy adapter injected into
indexing. That needs explicit design before implementation.

### `RagService -> ProtectedContentPolicy`

`RagService` uses `ProtectedContentPolicy` for:

- direct protected-path filtering of retrieved snippets;
- text sanitization before model context;
- integration with runtime context ledger records.

The direct path and sanitizer pieces are theoretically movable to safety, but
the class is already entangled with runtime context ledger contracts. Migrating
only the sanitizer/path calls would reduce a row while leaving the deeper RAG
ownership problem intact. Do not start here unless the ticket explicitly limits
it to direct path/sanitizer cleanup and acknowledges the context-ledger debt.

## Decision

### 1. Split read-only privacy config facts below runtime

Create a lower-level read-only component for privacy config facts.

Recommended owner:

```text
dev.talos.core.privacy.PrivacyConfigFacts
```

Why `core.privacy`, not `safety`:

- it depends on `Config` and `CfgUtil`, which are core types;
- `dev.talos.safety` is intentionally JDK-only and must not grow Talos-layer
  imports;
- tools and core can already depend on core;
- runtime can delegate to core facts while keeping approval-scope behavior.

Initial responsibilities:

- `privateMode(Config cfg)`;
- `ragEnabledInPrivateMode(Config cfg)`.

Explicit non-responsibilities:

- approved protected-read scope;
- send-to-model overrides;
- raw artifact persistence;
- `/privacy` mutation;
- user-facing approval notes;
- private-document model-handoff, raw artifact, or RAG decisions.

### 2. Keep `ProtectedReadScopePolicy` as runtime approval-scope policy

`ProtectedReadScopePolicy` should delegate read-only config facts to
`PrivacyConfigFacts`, but it should continue to own:

- `defaultScope(Config cfg)`;
- `sendApprovedProtectedReadToModel(Config cfg)`;
- `persistRawArtifacts(Config cfg)`;
- `setPrivateMode(Config cfg, boolean enabled)`;
- `approvedProtectedReadModelHandoffNote(Config cfg)`.

This preserves runtime semantics while removing lower-layer read-only callers
from runtime dependency.

### 3. Use `GrepTool` as the first privacy-config adopter

`GrepTool` is the right first implementation target because:

- it only needs `privateMode(cfg)`;
- it already has focused privacy tests for private-mode line withholding;
- it has no RAG/index metadata responsibilities;
- it has no approved protected-read scope behavior;
- removing its runtime dependency leaves the remaining grep behavior explicit.

Expected T353 result if scoped correctly:

- remove:
  `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`
- baseline `43 -> 42`;
- new violations `0`;
- stale baseline entries `0`;
- no model-handoff or artifact-persistence behavior changes.

### 4. Do not touch `PrivateDocumentPolicy` in T353

The private-document edges require a separate decision for the extracted
document decision contract.

Future decision target:

```text
[T354] Extracted Document Handoff Decision Contract
```

This should decide where a value object such as
`DocumentContentDecision(privateDocumentContent, modelHandoffAllowed,
rawArtifactPersistenceAllowed, ragIndexAllowed, reason)` belongs and whether it
is computed in runtime then consumed by tools/core, or whether lower-level
facts are injected into extraction/indexing.

### 5. Do not touch RAG context ledger in T353

`RagService` has runtime context imports:

- `ContextDecision`;
- `ContextItem`;
- `ContextItemSource`;
- `ContextLedgerCapture`;
- `ExecutionBoundary`.

That track needs a separate RAG/context contract decision. Do not hide it
behind a sanitizer/path migration.

### 6. Do not touch index privacy metadata in T353

`Indexer` still imports `ProtectedContentPolicy.POLICY_VERSION`. A correct
fix requires named lower-level version constants and index freshness tests.
That is not part of the privacy-config fact split.

## Remaining Baseline Classification

### T353 candidate: privacy config fact split

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`

Correct treatment:

- add `dev.talos.core.privacy.PrivacyConfigFacts`;
- make runtime `ProtectedReadScopePolicy.privateMode(...)` and
  `ragEnabledInPrivateMode(...)` delegate to it;
- migrate `GrepTool` to `PrivacyConfigFacts.privateMode(...)`;
- leave `RagService` for a later ticket.

### Later privacy-config adopter

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`

Correct treatment:

- migrate after T353 proves the config-fact split;
- keep context ledger imports unchanged unless a separate context contract
  ticket is active.

### Private document decision contract

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.PrivateDocumentPolicy`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ReadFileTool.java|dev.talos.runtime.policy.PrivateDocumentPolicy`

Correct treatment:

- design and introduce an extracted-document handoff/indexing decision
  contract;
- do not move `PrivateDocumentPolicy` wholesale.

### Index metadata and direct path cleanup

- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`

Correct treatment:

- split lower-level policy/version constants before migrating;
- direct path checks may use `ProtectedWorkspacePaths`, but metadata must be
  handled deliberately.

### RAG sanitizer/path plus context ledger

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextDecision`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItem`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextItemSource`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ContextLedgerCapture`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.context.ExecutionBoundary`

Correct treatment:

- decide whether RAG should emit core-owned retrieval evidence records and let
  runtime adapt them into context-ledger entries;
- avoid mixing this with T353.

### Separate tracks

These are outside the policy decision:

- runtime-to-CLI session/memory/result contracts;
- command/workspace execution contracts;
- SPI purity.

## Next Implementation Ticket

T353 should be:

```text
[T353] Extract privacy config facts for grep private mode
```

Recommended scope:

1. Add `dev.talos.core.privacy.PrivacyConfigFacts`.
2. Add tests proving:
   - developer mode is not private;
   - `private`, `strict`, and `strict_privacy` modes are private;
   - private-mode RAG is disabled by default;
   - private-mode RAG can be explicitly enabled.
3. Make `ProtectedReadScopePolicy.privateMode(...)` and
   `ragEnabledInPrivateMode(...)` delegate to `PrivacyConfigFacts`.
4. Migrate `GrepTool` from `ProtectedReadScopePolicy.privateMode(...)` to
   `PrivacyConfigFacts.privateMode(...)`.
5. Add an ownership test proving:
   - `GrepTool` imports `PrivacyConfigFacts`;
   - `GrepTool` no longer imports `ProtectedReadScopePolicy`;
   - the `GrepTool -> ProtectedReadScopePolicy` baseline entry is removed.
6. Run focused grep/private-mode/runtime policy tests.
7. Run `validateArchitectureBoundaries`.
8. Run full `check`.

Expected baseline result:

- Total: `42`
- New violations: `0`
- Stale baseline entries: `0`

The reason to do T353 is not that it is easy. The reason is that private-mode
configuration facts should not be owned by a runtime approved-read policy
class.

## Acceptance Criteria

- T352 records source-backed findings for the remaining policy-specific
  baseline edges.
- T352 explicitly rejects moving `PrivateDocumentPolicy` wholesale.
- T352 explicitly rejects using `RagService` as the first adopter for the
  privacy-config split.
- T352 names `dev.talos.core.privacy.PrivacyConfigFacts` as the lower owner
  for read-only privacy config facts.
- T352 keeps runtime approval-scope behavior in `ProtectedReadScopePolicy`.
- T352 names T353 as the next implementation ticket.
- T352 changes no production behavior.
- `git diff --check` passes, allowing repository line-ending warnings only.
- `validateReleaseLedger` and `validateArchitectureBoundaries` pass.
- No generated audit artifacts are committed.

## Verification

Planned before commit:

```powershell
git diff --check
.\gradlew.bat validateReleaseLedger validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Observed: passed. `git diff --check` passed; `validateReleaseLedger
validateArchitectureBoundaries` completed successfully; `check` completed
successfully, including unit tests, E2E tests, architecture validation, release
ledger validation, coverage verification, and generated artifact canary
scanning.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.
