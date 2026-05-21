# [T340-done-medium] Remove Indexed Symbol Checker Runtime Log Policy Edge

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T334-T340`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`
Predecessor: `[T339-done-high] harden-architecture-boundary-fqn-reference-scanner`

## Evidence Summary

- Source: architecture burn-down request after the T339 scanner hardening.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on
  `T334-T340`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: removed one `core-no-runtime` baseline edge by replacing a
  core-index debug log's runtime policy formatter dependency with a local
  non-content diagnostic.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused ownership test, focused behavior tests,
  architecture scanner, diff hygiene, and full `check` passed.

## Problem

`IndexedWorkspaceSymbolChecker` lives in `dev.talos.core.index`, but its
exception-path debug logging imported `dev.talos.runtime.policy.SafeLogFormatter`.
That created a `core-no-runtime` ownership edge even though the class only needs
to answer whether an indexed workspace symbol exists.

Moving `SafeLogFormatter` itself was intentionally skipped for this ticket
because it depends on `ProtectedContentPolicy`. Moving that formatter cleanly
would require a broader policy ownership decision, not a one-edge burn-down.

## Goal

Remove the `IndexedWorkspaceSymbolChecker -> SafeLogFormatter` boundary edge
without changing symbol lookup behavior or moving runtime policy classes.

## Non-Goals

- No `SafeLogFormatter` package move.
- No `ProtectedContentPolicy` package move.
- No Lucene indexing behavior change.
- No prompt-routing behavior change.
- No baseline growth.
- No broad logging-policy redesign.

## Implementation Summary

- Added an ownership regression test proving `IndexedWorkspaceSymbolChecker`
  does not reference `dev.talos.runtime.policy.SafeLogFormatter` in source or in
  the architecture baseline.
- Removed the `SafeLogFormatter` import from
  `IndexedWorkspaceSymbolChecker`.
- Replaced the exception-path debug message with a content-free local diagnostic
  that logs only the normalized symbol length and exception class name.
- Removed the matching baseline entry from
  `config/architecture-boundary-baseline.txt`.

## Architecture Metadata

Capability:

- Workspace symbol lookup and prompt-routing support.

Operation(s):

- Static ownership boundary cleanup.

Owning package/class:

- `dev.talos.core.index.IndexedWorkspaceSymbolChecker`.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The only runtime behavior changed is one debug log on symbol
  lookup exception paths.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus the real repository
  architecture scanner.
- Verification profile: focused ownership and symbol-checker tests, architecture
  validation, diff checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: remove one core-index runtime-policy logging edge.
- Forbidden: move runtime policy classes or change symbol lookup semantics.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest.indexedWorkspaceSymbolCheckerDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: failed because `IndexedWorkspaceSymbolChecker` and the architecture
baseline still referenced `dev.talos.runtime.policy.SafeLogFormatter`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest.indexedWorkspaceSymbolCheckerDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: passed after removing the runtime-policy formatter import, replacing
the exception-path debug message with a local non-content diagnostic, and
removing the baseline entry.

Focused behavior coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest" --tests "dev.talos.core.index.IndexedWorkspaceSymbolCheckerTest" --no-daemon
```

Result: passed.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `59` current and baselined forbidden references, `0` new
violations, and `0` stale entries.

Full check:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Acceptance Criteria

- `IndexedWorkspaceSymbolChecker` no longer references
  `dev.talos.runtime.policy.SafeLogFormatter`.
- The matching baseline entry is removed.
- `validateArchitectureBoundaries` passes with no new or stale violations.
- Focused index ownership and behavior tests pass.
- Full `check` passes.
- No generated audit artifacts are committed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The one affected debug log now reports less exception detail by design. This is
  acceptable because the old message created a core-to-runtime policy dependency
  for an exception-path diagnostic.
- Other `SafeLogFormatter` baseline edges remain. They should be evaluated one
  at a time because some may carry real protected-content policy semantics.

## Known Follow-Ups

- Continue burn-down against the remaining baseline using one-edge tickets.
- Reconsider `SafeLogFormatter` ownership only after deciding where
  `ProtectedContentPolicy` belongs.
