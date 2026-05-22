# [T354-done-medium] Extract Privacy Config Facts For Rag Private Mode

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T354`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T353-done-medium] extract-privacy-config-facts-for-grep-private-mode`

## Evidence Summary

- Source: T353 follow-up after PR #18 merged into `v0.9.0-beta-dev`.
- Date: 2026-05-22.
- Base branch: `origin/v0.9.0-beta-dev` at
  `b4a757c27b1e04386299ae934819e70977982197`.
- Beta push CI: run `#50`, `Beta Dev CI`, push event for `b4a757c2`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T354`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: migrated only `RagService` private-mode RAG config fact
  reads from runtime `ProtectedReadScopePolicy` to lower-level
  `PrivacyConfigFacts`.
- Verification status: RED/GREEN ownership test, focused RAG/privacy tests, and
  architecture scanner passed before the final gate.

## Problem

After T353, `RagService` still had one runtime policy dependency for read-only
privacy config facts:

```text
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy
```

The source usage was narrow. `RagService` only asked:

```text
Is private mode enabled?
Is RAG enabled while private mode is active?
```

Those facts are already owned by `dev.talos.core.privacy.PrivacyConfigFacts`.
Keeping this read-only decision behind `ProtectedReadScopePolicy` made core RAG
depend on a runtime approved-read policy class for no runtime approval-scope
reason.

## Goal

Move only `RagService` private-mode RAG config fact reads to
`PrivacyConfigFacts` while preserving RAG refusal, context-ledger recording, lazy
indexing behavior, protected path filtering, snippet sanitization, and all
runtime context contracts.

## Non-Goals

- No `ProtectedContentPolicy` move.
- No `PrivateDocumentPolicy` move.
- No RAG runtime context ledger contract move.
- No `ToolCallParser` move.
- No index metadata or policy-version change.
- No private-document model-handoff or RAG-indexing decision change.
- No approved protected-read scope change.
- No artifact persistence change.
- No CLI/runtime contract work.
- No baseline growth.

## Implementation Summary

- Updated `RagService.reindex(...)` to use:
  - `PrivacyConfigFacts.privateMode(cfg)`;
  - `PrivacyConfigFacts.ragEnabledInPrivateMode(cfg)`.
- Updated `RagService.prepare(...)` to use the same lower-level facts for the
  private-mode RAG refusal path.
- Updated `RagService.ensureIndexExists(...)` to use the same lower-level facts
  for lazy-indexing refusal.
- Added a focused ownership test in `RagServiceContextLedgerTest`.
- Removed only the stale `RagService -> ProtectedReadScopePolicy` baseline row.

## Architecture Metadata

Capability:

- Read-only privacy config facts for RAG private-mode gating.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving config fact adoption.
- One architecture baseline reduction.

Owning package/class:

- Read-only privacy facts:
  `dev.talos.core.privacy.PrivacyConfigFacts`
- Runtime approved protected-read policy:
  `dev.talos.runtime.policy.ProtectedReadScopePolicy`
- RAG private-mode adapter:
  `dev.talos.core.rag.RagService`

Risk, approval, and protected paths:

- Risk level: medium. RAG private-mode gating is privacy-sensitive, so this
  ticket uses RED/GREEN ownership tests plus focused RAG/privacy tests.
- Approval behavior: not changed.
- Protected path behavior: not changed.
- Private-mode RAG refusal: intended to be unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN ownership test, focused RAG/privacy tests, and
  real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  hygiene, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: migrate `RagService` private-mode RAG config fact reads to
  `PrivacyConfigFacts`.
- Forbidden: move private-document policy, content sanitization/protected path
  policy, RAG context ledger contracts, tool-call parsing, index metadata,
  artifact persistence, command policy, or CLI/runtime contracts.

## Baseline Result

Before T354, the architecture baseline had `42` entries after T353 merged.

T354 removes:

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`

New baseline result:

- Total: `41`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest.ragServiceUsesCorePrivacyFactsForPrivateModeRagOwnership" --no-daemon
```

Expected and observed: failed before implementation because `RagService` still
imported `ProtectedReadScopePolicy` and the baseline still contained the stale
row.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest.ragServiceUsesCorePrivacyFactsForPrivateModeRagOwnership" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest" --tests "dev.talos.core.rag.RagDirtyIndexIntegrationTest" --tests "dev.talos.core.privacy.PrivacyConfigFactsTest" --tests "dev.talos.runtime.policy.ProtectedReadScopePolicyTest" --tests "dev.talos.cli.launcher.RagIndexCmdPrivateModeTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=41`,
`baselineCount=41`, `newViolationCount=0`, and `staleBaselineCount=0`.

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

Do not mechanically continue into `PrivateDocumentPolicy`, RAG index metadata,
or runtime context ledger contracts. The next implementation ticket should be
selected from the remaining baseline after T354 merges and beta push CI passes.
