# [T356-done-medium] Move Indexer Protected Content Version To Safety

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T356`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T355-done-medium] extract-safety-primitives-for-rag-protected-content`

## Evidence Summary

- Source: post-T355 inspection after PR #20 merged into `v0.9.0-beta-dev`.
- Date: 2026-05-22.
- Base branch: `origin/v0.9.0-beta-dev` at
  `dbfe625edce10c1f57182b51f3f7fd53630b0a8a`.
- Beta push CI: run `#56`, `Beta Dev CI`, push event for `dbfe625e`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T356`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: moved `Indexer` direct protected-path checks and index
  protected-content freshness version off runtime `ProtectedContentPolicy` and
  onto lower-level safety ownership.
- Verification status: RED/GREEN ownership test, focused index/safety/runtime
  policy tests, and architecture scanner passed before the final gate.

## Problem

After T355, `Indexer` still had one runtime policy dependency:

```text
core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy
```

Inspection showed this was not a simple path-call migration. `Indexer` used
`ProtectedContentPolicy` for:

- `POLICY_VERSION` in index freshness metadata;
- direct protected-path exclusion before indexing.

The direct path checks belong to `dev.talos.safety.ProtectedWorkspacePaths`, but
the metadata version needed deliberate handling. Changing the metadata key or
version value casually would either force unnecessary reindexing or fail to
invalidate stale unsafe indexes later.

## Goal

Move `Indexer` off runtime `ProtectedContentPolicy` while preserving existing
index metadata semantics:

- keep the `privacyPolicyVersion` metadata key stable;
- keep the policy version string stable;
- move the version owner to the lower-level protected workspace path classifier;
- keep runtime `ProtectedContentPolicy.POLICY_VERSION` as a compatibility
  facade that delegates to the safety owner;
- migrate only direct protected-path checks to `ProtectedWorkspacePaths`.

## Non-Goals

- No `PrivateDocumentPolicy` move.
- No document extraction handoff/indexing decision move.
- No RAG context ledger/runtime context contract move.
- No `ToolCallParser` move.
- No index schema version bump.
- No metadata key rename.
- No policy version value change.
- No artifact persistence change.
- No CLI/runtime contract work.
- No baseline growth.

## Implementation Summary

- Added `ProtectedWorkspacePaths.POLICY_VERSION` with the existing stable value:
  `protected-content-policy-v2`.
- Updated runtime `ProtectedContentPolicy.POLICY_VERSION` to delegate to
  `ProtectedWorkspacePaths.POLICY_VERSION`.
- Updated `Indexer.isPolicyMetadataCurrent(...)` to compare
  `privacyPolicyVersion` against `ProtectedWorkspacePaths.POLICY_VERSION`.
- Updated `Indexer.writePolicyMetadata(...)` to persist
  `ProtectedWorkspacePaths.POLICY_VERSION`.
- Updated both index file filters to call
  `ProtectedWorkspacePaths.isProtectedPath(...)`.
- Updated `IndexerPolicyMetadataTest` to assert the safety-owned metadata
  version and source ownership.
- Removed only the stale `Indexer -> ProtectedContentPolicy` baseline row.

## Architecture Metadata

Capability:

- Protected workspace path exclusion for RAG indexing.
- Index freshness metadata for protected-content path policy changes.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving policy-version ownership split.
- One architecture baseline reduction.

Owning package/class:

- Protected path classification:
  `dev.talos.safety.ProtectedWorkspacePaths`
- Runtime facade retained:
  `dev.talos.runtime.policy.ProtectedContentPolicy`
- Index adapter:
  `dev.talos.core.index.Indexer`

Risk, approval, and protected paths:

- Risk level: medium. Index metadata and protected-path exclusion are
  privacy-sensitive, so the ticket uses RED/GREEN ownership tests plus focused
  metadata, index privacy, path, and runtime facade tests.
- Approval behavior: not changed.
- Protected path behavior: intended to be unchanged.
- Index metadata key/value: intended to be unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN ownership test, focused index/safety/runtime
  policy tests, and real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  hygiene, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: migrate `Indexer` protected-path filtering and protected-content
  freshness version ownership to safety.
- Forbidden: move private-document policy, RAG context ledger contracts,
  tool-call parsing, document handoff/indexing decisions, artifact persistence,
  command policy, or CLI/runtime contracts.

## Baseline Result

Before T356, the architecture baseline had `40` entries after T355 merged.

T356 removes:

- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `39`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.IndexerPolicyMetadataTest.indexer_uses_safety_path_policy_version_for_protected_content_ownership" --no-daemon
```

Expected and observed: failed before implementation because `Indexer` still
imported `ProtectedContentPolicy`, used the runtime policy version, and the
baseline still contained the stale row.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.IndexerPolicyMetadataTest.indexer_uses_safety_path_policy_version_for_protected_content_ownership" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.IndexerPolicyMetadataTest" --tests "dev.talos.core.index.IndexerPrivateDocumentPolicyTest" --tests "dev.talos.core.index.IndexerCaseTest" --tests "dev.talos.core.rag.RagDirtyIndexIntegrationTest" --tests "dev.talos.safety.ProtectedWorkspacePathsTest" --tests "dev.talos.safety.SafetyOwnershipTest" --tests "dev.talos.runtime.policy.ProtectedContentPolicyTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=39`,
`baselineCount=39`, `newViolationCount=0`, and `staleBaselineCount=0`.

Final gate before commit:

```powershell
git diff --check
.\gradlew.bat validateReleaseLedger validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Observed: passed. `git diff --check` reported repository line-ending warnings
only; `validateReleaseLedger validateArchitectureBoundaries` completed
successfully; `check` completed successfully, including unit tests, E2E tests,
architecture validation, release ledger validation, coverage verification, and
generated artifact canary scanning.

## Follow-Up

Do not mechanically continue into `PrivateDocumentPolicy`. The remaining
private-document edges require the explicit extracted-document handoff/indexing
decision contract described in T349 and T352.
