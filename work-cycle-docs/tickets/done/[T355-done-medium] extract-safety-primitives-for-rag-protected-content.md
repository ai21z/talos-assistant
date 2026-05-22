# [T355-done-medium] Extract Safety Primitives For Rag Protected Content

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T355`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T354-done-medium] extract-privacy-config-facts-for-rag-private-mode`

## Evidence Summary

- Source: post-T354 inspection after PR #19 merged into `v0.9.0-beta-dev`.
- Date: 2026-05-22.
- Base branch: `origin/v0.9.0-beta-dev` at
  `3b586d2890ab3fdb33d13726825c2615bab7e4a5`.
- Beta push CI: run `#53`, `Beta Dev CI`, push event for `3b586d28`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T355`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: migrated only `RagService` direct protected-path filtering
  and text sanitization from runtime `ProtectedContentPolicy` to neutral safety
  primitives.
- Verification status: RED/GREEN ownership test, focused RAG/safety tests, and
  architecture scanner passed before the final gate.

## Problem

After T354, `RagService` still had one runtime policy dependency for pure safety
primitives:

```text
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy
```

The source usage was narrow:

```text
ProtectedContentPolicy.isProtectedPath(...)
ProtectedContentPolicy.sanitizeText(...)
```

Those calls do not need runtime policy ownership. T346 and T350 already split
the pure lower-level primitives into:

- `dev.talos.safety.ProtectedWorkspacePaths`
- `dev.talos.safety.ProtectedContentSanitizer`

Keeping `RagService` on `ProtectedContentPolicy` made core RAG depend on a
runtime policy class for operations already owned by the safety layer.

## Goal

Move only `RagService` protected-path filtering and snippet text sanitization to
neutral safety primitives while preserving RAG retrieval behavior, context-ledger
recording, private-mode gating, index metadata behavior, and model-answer
generation behavior.

## Non-Goals

- No `PrivateDocumentPolicy` move.
- No RAG context ledger/runtime context contract move.
- No `ToolCallParser` move.
- No index metadata or policy-version change.
- No private-document model-handoff or RAG-indexing decision change.
- No approved protected-read scope change.
- No artifact persistence change.
- No CLI/runtime contract work.
- No baseline growth.

## Implementation Summary

- Updated `RagService` to use:
  - `ProtectedWorkspacePaths.isProtectedPath(ws, snippetPath)`;
  - `ProtectedContentSanitizer.sanitizeText(text)`.
- Removed the `ProtectedContentPolicy` import from `RagService`.
- Added a focused ownership test in `RagServiceContextLedgerTest`.
- Removed only the stale `RagService -> ProtectedContentPolicy` baseline row.

## Architecture Metadata

Capability:

- Direct protected-path filtering and sink-safe snippet text sanitization for
  RAG retrieval results.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving safety primitive adoption.
- One architecture baseline reduction.

Owning package/class:

- Protected path classification:
  `dev.talos.safety.ProtectedWorkspacePaths`
- Text sanitization:
  `dev.talos.safety.ProtectedContentSanitizer`
- RAG adapter:
  `dev.talos.core.rag.RagService`

Risk, approval, and protected paths:

- Risk level: medium. RAG protected-path exclusion and snippet sanitization are
  privacy-sensitive, so this ticket uses RED/GREEN ownership tests plus focused
  RAG/safety tests.
- Approval behavior: not changed.
- Protected path behavior: intended to be unchanged.
- Private-mode RAG gating: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN ownership test, focused RAG/safety tests, and
  real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  hygiene, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: migrate `RagService` direct protected-path and text sanitizer calls
  to safety primitives.
- Forbidden: move private-document policy, RAG context ledger contracts,
  tool-call parsing, index metadata, artifact persistence, command policy, or
  CLI/runtime contracts.

## Baseline Result

Before T355, the architecture baseline had `41` entries after T354 merged.

T355 removes:

- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `40`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest.ragServiceUsesSafetyPrimitivesForProtectedContentOwnership" --no-daemon
```

Expected and observed: failed before implementation because `RagService` still
imported `ProtectedContentPolicy` and the baseline still contained the stale
row.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest.ragServiceUsesSafetyPrimitivesForProtectedContentOwnership" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.rag.RagServiceContextLedgerTest" --tests "dev.talos.core.rag.RagDirtyIndexIntegrationTest" --tests "dev.talos.safety.ProtectedContentSanitizerTest" --tests "dev.talos.safety.ProtectedWorkspacePathsTest" --tests "dev.talos.safety.SafetyOwnershipTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=40`,
`baselineCount=40`, `newViolationCount=0`, and `staleBaselineCount=0`.

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
selected from the remaining baseline after T355 merges and beta push CI passes.
