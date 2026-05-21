# [T348-done-medium] Move Document Extraction Service Sanitizer To Safety

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T348`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T347-done-medium] move-document-preflight-sanitizer-to-safety`

## Evidence Summary

- Source: post-T347 architecture ratchet continuation after PR #12 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T348`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `6a978bf4ebb1a6e6fc220affffb9e0432ec6b696`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: replaced `DocumentExtractionService`'s pure text
  redaction calls from runtime `ProtectedContentPolicy` to neutral
  `dev.talos.safety.ProtectedContentSanitizer`.
- Verification status: RED/GREEN ownership test, focused extraction/safety
  tests, architecture scanner, release ledger validation, diff hygiene, and
  full `check` passed.

## Problem

After T346 and T347, pure redaction primitives belong to `dev.talos.safety`.
`DocumentExtractionService` still imported
`dev.talos.runtime.policy.ProtectedContentPolicy` only for
`sanitizeText(...)` calls.

That import was no longer an honest ownership edge. The service did not need
tool-result sanitization, approval state, workspace protected-path
classification, or runtime trace behavior for those calls. It only needed pure
text redaction before returning extraction output and warning text.

The same class still imports `PrivateDocumentPolicy`, but that is deliberately
out of scope for T348 because it represents mixed private-mode/model-handoff
policy, not pure redaction.

## Goal

Remove the `DocumentExtractionService -> ProtectedContentPolicy` dependency by
using the neutral safety sanitizer for pure text redaction.

## Non-Goals

- No `PrivateDocumentPolicy` move.
- No protected-read-scope redesign.
- No RAG/index privacy policy move.
- No CLI/runtime session contract cleanup.
- No command/workspace contract cleanup.
- No document extraction behavior change.
- No OCR command behavior change.
- No baseline growth.

## Implementation Summary

- Added a source ownership regression in `DocumentExtractionServiceTest`.
- Updated `DocumentExtractionService` to import
  `dev.talos.safety.ProtectedContentSanitizer`.
- Replaced only `ProtectedContentPolicy.sanitizeText(...)` calls with
  `ProtectedContentSanitizer.sanitizeText(...)`.
- Left `PrivateDocumentPolicy` untouched.
- Removed the matching `core-no-runtime` baseline entry.

## Architecture Metadata

Capability:

- Document extraction text and warning redaction.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving dependency relocation.

Owning package/class:

- `dev.talos.core.extract.DocumentExtractionService`
- Neutral sanitizer owner: `dev.talos.safety.ProtectedContentSanitizer`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The sanitizer implementation is the same neutral primitive
  introduced in T346; only the import owner changes for pure text redaction.
- Approval behavior: not changed.
- Protected path behavior: not changed.
- Private-mode behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus real architecture
  scanner output.
- Verification profile: focused extraction/safety tests, architecture
  validation, diff checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: replace pure text sanitizer dependency with neutral safety package.
- Forbidden: move mixed private-document policy or reinterpret private-mode
  handoff behavior.

## Baseline Result

Before T348, the architecture baseline had `46` entries after T347 merged.

T348 removes:

- `core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `45`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest.service_uses_neutral_sanitizer_for_text_redaction_but_keeps_private_document_policy" --no-daemon
```

Expected and observed: failed before implementation because
`DocumentExtractionService` still imported runtime `ProtectedContentPolicy`.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest.service_uses_neutral_sanitizer_for_text_redaction_but_keeps_private_document_policy" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.safety.SafetyOwnershipTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=45`,
`baselineCount=45`, `newViolationCount=0`, and `staleBaselineCount=0`.

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

The remaining `DocumentExtractionService -> PrivateDocumentPolicy` edge should
not be treated as the same cleanup. It needs a separate ownership decision or
narrow decision interface because it controls private-mode/model-handoff
behavior.
