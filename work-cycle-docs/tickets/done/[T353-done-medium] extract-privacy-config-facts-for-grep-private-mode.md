# [T353-done-medium] Extract Privacy Config Facts For Grep Private Mode

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T353`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T352-done-high] remaining-policy-boundary-ownership-decision`

## Evidence Summary

- Source: T352 ownership decision after PR #17 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-22.
- Base branch: `origin/v0.9.0-beta-dev` at
  `40b06b7f314e395ce57e65fc72254c3d72febddf`.
- Beta push CI: run `#47`, `Beta Dev CI`, push event for `40b06b7f`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T353`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: added a lower-level read-only privacy config facts class,
  made runtime `ProtectedReadScopePolicy` delegate read-only privacy facts to
  it, and migrated only `GrepTool` off runtime `ProtectedReadScopePolicy`.
- Verification status: RED/GREEN privacy fact and ownership tests, focused
  grep/privacy/runtime policy tests, and architecture scanner passed before
  the final gate.

## Problem

After T351, `GrepTool` had one remaining runtime policy dependency:

```text
tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy
```

The source usage was narrow. `GrepTool` only asked:

```text
Is this config in private mode?
```

It did not need runtime approved protected-read scope, send-to-model override,
raw artifact persistence, approval note wording, `/privacy` mutation behavior,
or any private-document decision.

Keeping that read-only config fact inside `ProtectedReadScopePolicy` forced
tools to import a runtime approval-scope policy class for a lower-level fact.

## Goal

Split read-only privacy config facts below runtime and migrate `GrepTool` as
the first adopter without changing private-mode behavior, protected-read
approval scope, document handoff, artifact persistence, RAG/indexing, or index
metadata.

## Non-Goals

- No `PrivateDocumentPolicy` move.
- No `RagService` migration.
- No index metadata or policy-version changes.
- No approved protected-read default-scope changes.
- No send-to-model override changes.
- No raw artifact persistence changes.
- No `/privacy` command behavior changes.
- No private-document model-handoff or RAG-indexing decision changes.
- No runtime context ledger work.
- No command/workspace or CLI/runtime contract work.
- No baseline growth.

## Implementation Summary

- Added `dev.talos.core.privacy.PrivacyConfigFacts`.
- `PrivacyConfigFacts` owns read-only privacy config facts:
  - `privateMode(Config cfg)`;
  - `ragEnabledInPrivateMode(Config cfg)`.
- Updated `ProtectedReadScopePolicy.privateMode(...)` to delegate to
  `PrivacyConfigFacts.privateMode(...)`.
- Updated `ProtectedReadScopePolicy.ragEnabledInPrivateMode(...)` to delegate
  to `PrivacyConfigFacts.ragEnabledInPrivateMode(...)`.
- Kept runtime `ProtectedReadScopePolicy` ownership for:
  - approved protected-read default scope;
  - send-approved-protected-read-to-model;
  - raw artifact persistence;
  - private-mode mutation;
  - user-facing approved-read handoff notes.
- Updated `GrepTool` to use `PrivacyConfigFacts.privateMode(ctx.config())`.
- Removed only the stale `GrepTool -> ProtectedReadScopePolicy` baseline row.

## Architecture Metadata

Capability:

- Read-only privacy config facts for tools, core, and runtime.
- Grep private-mode search result withholding remains behaviorally unchanged.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving config fact extraction.
- One architecture baseline reduction.

Owning package/class:

- Read-only privacy facts:
  `dev.talos.core.privacy.PrivacyConfigFacts`
- Runtime approved protected-read policy:
  `dev.talos.runtime.policy.ProtectedReadScopePolicy`
- Grep private-mode adapter:
  `dev.talos.tools.impl.GrepTool`

Risk, approval, and protected paths:

- Risk level: medium. Private-mode behavior is privacy-sensitive, so this
  ticket uses RED/GREEN ownership tests and focused grep private-mode tests.
- Approval behavior: not changed.
- Protected path behavior: not changed.
- Private-mode grep withholding: intended to be unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN tests, focused grep/private-mode/runtime
  policy tests, and real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  hygiene, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: split read-only privacy config facts and migrate `GrepTool`.
- Forbidden: move private-document policy, approval scope, RAG/indexing privacy
  semantics, index metadata, context ledger contracts, command policy, or
  CLI/runtime contracts.

## Baseline Result

Before T353, the architecture baseline had `43` entries after T352 merged.

T353 removes:

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedReadScopePolicy`

New baseline result:

- Total: `42`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.privacy.PrivacyConfigFactsTest" --tests "dev.talos.tools.impl.GrepToolTest.grep_uses_core_privacy_facts_for_private_mode_ownership" --no-daemon
```

Expected and observed: failed before implementation because
`PrivacyConfigFacts` did not exist.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.privacy.PrivacyConfigFactsTest" --tests "dev.talos.tools.impl.GrepToolTest.grep_uses_core_privacy_facts_for_private_mode_ownership" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.core.privacy.PrivacyConfigFactsTest" --tests "dev.talos.runtime.policy.ProtectedReadScopePolicyTest" --tests "dev.talos.core.ConfigPrivacyDefaultsTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=42`,
`baselineCount=42`, `newViolationCount=0`, and `staleBaselineCount=0`.

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

Do not mechanically continue into `PrivateDocumentPolicy` or index metadata.
The next good implementation candidate after T353 merges is likely the later
privacy-config adopter:

```text
core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.ProtectedReadScopePolicy
```

That should be its own ticket, and it must avoid changing runtime context
ledger contracts in the same packet. The `PrivateDocumentPolicy` edges still
need the separate extracted-document handoff decision contract described in
T352.
