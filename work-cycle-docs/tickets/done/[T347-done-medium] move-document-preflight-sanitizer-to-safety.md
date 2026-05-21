# [T347-done-medium] Move Document Preflight Sanitizer To Safety

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T347`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T346-done-high] extract-neutral-sink-safety-primitives`

## Evidence Summary

- Source: post-T346 architecture ratchet continuation after PR #11 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T347`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: replaced `DocumentExtractionPreflight`'s runtime
  `ProtectedContentPolicy` import with neutral
  `dev.talos.safety.ProtectedContentSanitizer`.
- Verification status: RED/GREEN ownership test, focused preflight/safety
  tests, architecture scanner, release ledger validation, diff hygiene, and
  full `check` passed.

## Problem

After T346, pure text redaction belongs to `dev.talos.safety`, but
`DocumentExtractionPreflight` still imported
`dev.talos.runtime.policy.ProtectedContentPolicy` only to sanitize status
summary/detail strings.

That is not runtime policy. The preflight class does not need tool-result
sanitization, workspace path classification, private-mode policy, approval
scope, or runtime state. It only needs pure sink-safety text redaction.

## Goal

Remove the `DocumentExtractionPreflight -> ProtectedContentPolicy`
package-direction edge by using the neutral safety sanitizer created in T346.

## Non-Goals

- No document extraction behavior change.
- No `DocumentExtractionService` policy split.
- No `PrivateDocumentPolicy` move.
- No protected-read-scope redesign.
- No OCR command execution behavior change.
- No baseline growth.

## Implementation Summary

- Added a source ownership regression in `DocumentExtractionPreflightTest`.
- Updated `DocumentExtractionPreflight.FamilyStatus` to call
  `ProtectedContentSanitizer.sanitizeText(...)`.
- Removed the matching `core-no-runtime` baseline entry.

## Architecture Metadata

Capability:

- Document extraction status/preflight rendering.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving dependency relocation.

Owning package/class:

- `dev.talos.core.extract.DocumentExtractionPreflight`
- Neutral sanitizer owner: `dev.talos.safety.ProtectedContentSanitizer`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The sanitizer implementation is the same pure primitive
  extracted in T346; the call site changes only its owner import.
- Approval behavior: not changed.
- Protected path behavior: not changed.
- Private-mode behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus real architecture
  scanner output.
- Verification profile: focused preflight test, architecture validation, diff
  checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: replace pure text sanitizer dependency with neutral safety package.
- Forbidden: move mixed runtime policy classes or reinterpret private document
  handoff behavior.

## Baseline Result

Before T347, the architecture baseline had `47` entries after T346 merged.

T347 removes:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionPreflight.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `46`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionPreflightTest.preflight_uses_neutral_sanitizer_instead_of_runtime_policy" --no-daemon
```

Expected and observed: failed before implementation because
`DocumentExtractionPreflight` still imported runtime `ProtectedContentPolicy`.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionPreflightTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=46`,
`baselineCount=46`, `newViolationCount=0`, and `staleBaselineCount=0`.

Final gate before commit:

```powershell
git diff --check
.\gradlew.bat check --no-daemon
```

Observed: passed. `git diff --check` reported repository line-ending warnings
only; `check` completed successfully, including unit tests, E2E tests,
architecture validation, release ledger validation, coverage verification, and
generated artifact canary scanning.

## Follow-Up

The next protected-content cleanup should continue separating pure safety
redaction from mixed runtime policy. Do not move `ProtectedContentPolicy`,
`PrivateDocumentPolicy`, or `ProtectedReadScopePolicy` wholesale.
