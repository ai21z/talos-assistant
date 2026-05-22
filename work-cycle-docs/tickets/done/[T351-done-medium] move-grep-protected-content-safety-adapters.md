# [T351-done-medium] Move Grep Protected Content Safety Adapters

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T351`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T350-done-medium] extract-direct-protected-workspace-path-classifier`

## Evidence Summary

- Source: post-T350 architecture ratchet continuation after PR #15 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Base branch: `origin/v0.9.0-beta-dev` at
  `2573747d31a5a81986102e0581294f1fb64f8e8c`.
- Beta push CI: run `#41`, `Beta Dev CI`, push event for `2573747d`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T351`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: moved `GrepTool` direct protected-path checks, search
  text redaction, and protected-content skip note wording off runtime
  `ProtectedContentPolicy` and onto neutral safety adapters.
- Verification status: RED/GREEN ownership tests, focused grep/safety/runtime
  redaction tests, and architecture scanner passed before the final gate.

## Problem

T350 proved `dev.talos.safety.ProtectedWorkspacePaths` as the owner of direct
workspace protected-path classification. After that, `GrepTool` still imported
`dev.talos.runtime.policy.ProtectedContentPolicy` for three non-runtime
operations:

- direct protected-path skip checks while walking files;
- pure text/search-line sanitization;
- protected-content skip note wording.

Those operations are sink-safety and direct workspace classification concerns,
not runtime approval scope. Keeping them behind `ProtectedContentPolicy`
preserved an ownership lie in `tools-no-runtime`.

`GrepTool` also imports `ProtectedReadScopePolicy` for private-mode search
withholding. That is a separate protected-read/private-mode behavior and stays
out of this ticket.

## Goal

Remove only the `GrepTool -> ProtectedContentPolicy` architecture edge while
preserving grep search behavior, protected path omission, output redaction,
protected-content note wording, and private-mode withholding.

## Non-Goals

- No `ProtectedReadScopePolicy` move.
- No private-mode search-line withholding redesign.
- No `PrivateDocumentPolicy` move.
- No RAG/indexing changes.
- No `Indexer` policy-version metadata changes.
- No `/grep` slash command migration.
- No runtime-to-CLI boundary work.
- No command/workspace contract work.
- No baseline growth.

## Implementation Summary

- Added `dev.talos.safety.ProtectedContentMessages` for pure
  protected-content note wording.
- Made runtime `ProtectedContentPolicy.PROTECTED_CONTENT_NOTE` and
  `protectedContentNote(...)` delegate to `ProtectedContentMessages`, preserving
  the runtime facade for existing runtime callers.
- Updated `GrepTool` to use:
  - `ProtectedWorkspacePaths.isProtectedPath(...)`;
  - `ProtectedContentSanitizer.sanitizeText(...)`;
  - `ProtectedContentSanitizer.sanitizeSearchLine(...)`;
  - `ProtectedContentMessages.protectedContentNote(...)`.
- Kept `GrepTool -> ProtectedReadScopePolicy` intact.
- Removed only the stale `GrepTool -> ProtectedContentPolicy` baseline entry.

## Architecture Metadata

Capability:

- Workspace grep protected-path skipping and sink-safe result rendering.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving adapter migration.
- One architecture baseline reduction.

Owning package/class:

- Direct workspace path classifier:
  `dev.talos.safety.ProtectedWorkspacePaths`
- Text/search-line sanitizer:
  `dev.talos.safety.ProtectedContentSanitizer`
- Protected-content note wording:
  `dev.talos.safety.ProtectedContentMessages`
- Runtime compatibility facade:
  `dev.talos.runtime.policy.ProtectedContentPolicy`
- Private-mode grep withholding:
  `dev.talos.runtime.policy.ProtectedReadScopePolicy`

Risk, approval, and protected paths:

- Risk level: medium. Grep is a privacy-sensitive read-only tool, so this ticket
  uses RED/GREEN source ownership tests and focused grep privacy tests.
- Approval behavior: not changed.
- Protected path behavior: intended to be unchanged.
- Private-mode behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN ownership tests, focused grep privacy tests,
  safety ownership checks, runtime redaction compatibility, and real
  architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  hygiene, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: migrate `GrepTool` off runtime `ProtectedContentPolicy` for direct
  path classification, pure sanitizer calls, and protected-content note
  wording.
- Forbidden: move protected-read scope, private-document behavior,
  RAG/indexing privacy semantics, tool-call classification, command policy, or
  CLI/runtime contracts.

## Baseline Result

Before T351, the architecture baseline had `44` entries after T350 merged.

T351 removes:

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/GrepTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `43`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest.grep_uses_neutral_safety_for_protected_content_path_and_sanitizer_ownership" --no-daemon
.\gradlew.bat test --tests "dev.talos.safety.SafetyOwnershipTest.sinkSafetyPackageOwnsSafeLogFormatterAndPurePrimitives" --no-daemon
```

Expected and observed: failed before implementation because `GrepTool` still
imported `ProtectedContentPolicy`, the baseline still contained that edge, and
`ProtectedContentMessages` did not exist.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest.grep_uses_neutral_safety_for_protected_content_path_and_sanitizer_ownership" --tests "dev.talos.safety.SafetyOwnershipTest.sinkSafetyPackageOwnsSafeLogFormatterAndPurePrimitives" --tests "dev.talos.tools.impl.GrepToolTest.grep_does_not_leak_env_canary" --tests "dev.talos.tools.impl.GrepToolTest.privateModeGrepDoesNotExposeNeighborFieldsAroundCanaryMatches" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.GrepToolTest" --tests "dev.talos.safety.*" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --tests "dev.talos.runtime.policy.ProtectedPathPolicyTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=43`,
`baselineCount=43`, `newViolationCount=0`, and `staleBaselineCount=0`.

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

Do not continue mechanically into `GrepTool -> ProtectedReadScopePolicy`.
That edge owns private-mode search behavior and needs a separate protected-read
scope/config-fact split before implementation. The next ticket should either
address another clearly classified direct path/sanitizer edge or pause for the
next ownership decision if the remaining baseline entries are mixed.
