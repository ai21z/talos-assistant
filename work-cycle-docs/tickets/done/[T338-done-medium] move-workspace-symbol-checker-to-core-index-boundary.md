# [T338-done-medium] Move Workspace Symbol Checker To Core Index Boundary

Status: done
Priority: medium
Date: 2026-05-21
Branch: `codex/architecture-hygiene-ratchet`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`
Predecessor: `[T337-done-medium] move-tool-alias-policy-to-tools-boundary`

## Evidence Summary

- Source: post-T337 architecture ratchet selection.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on
  `codex/architecture-hygiene-ratchet`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: moved the workspace symbol-checker contract from CLI modes
  to core indexing, updated imports, and reduced the architecture boundary
  baseline.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused tests and architecture scanner passed.

## Problem

The T336 baseline still contained this clean ownership mismatch:

```text
runtime-core-no-cli|src/main/java/dev/talos/core/index/IndexedWorkspaceSymbolChecker.java|dev.talos.cli.modes.WorkspaceSymbolChecker
```

`WorkspaceSymbolChecker` is a pure contract for checking whether a PascalCase
symbol exists in the indexed workspace. Its Lucene implementation is already in
`core.index`, but the interface was owned by `cli.modes`, forcing core indexing
to depend upward on CLI routing.

## Goal

Move the symbol-checker contract to `dev.talos.core.index` and remove the stale
core-to-CLI baseline entry without changing prompt classification or index
lookup behavior.

## Non-Goals

- No prompt-routing behavior change.
- No Lucene lookup behavior change.
- No broader CLI/runtime/core split.
- No `SafeLogFormatter` or protected-content policy move.
- No DI framework.

## Implementation Summary

Moved:

- `src/main/java/dev/talos/cli/modes/WorkspaceSymbolChecker.java`
  -> `src/main/java/dev/talos/core/index/WorkspaceSymbolChecker.java`

Updated imports in:

- `src/main/java/dev/talos/cli/modes/ModeController.java`
- `src/main/java/dev/talos/cli/modes/PromptClassifier.java`
- `src/main/java/dev/talos/cli/repl/slash/RouteCommand.java`
- affected classifier, controller, and route tests

Updated:

- `config/architecture-boundary-baseline.txt`

Architecture baseline count changed:

```text
Before: 61 forbidden import edges
After:  60 forbidden import edges
```

## Architecture Metadata

Capability:

- Workspace symbol lookup contract used by prompt classification.

Operation(s):

- Behavior-preserving package move.
- Static boundary debt reduction.

Owning package/class:

- `dev.talos.core.index.WorkspaceSymbolChecker`
- `dev.talos.core.index.IndexedWorkspaceSymbolChecker`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low runtime risk; low compile/import blast radius.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: architecture scanner must show one fewer baselined
  forbidden edge and no new/stale drift.
- Verification profile: focused classifier/controller/index tests plus
  `validateArchitectureBoundaries`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: move the interface and import sites.
- Forbidden: changing prompt classification, index lookup semantics, or routing
  thresholds.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest" --no-daemon
```

Result: failed because `WorkspaceSymbolChecker` did not exist under
`dev.talos.core.index`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest" --no-daemon
```

Result: passed after the move.

Focused behavior checks:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.WorkspaceSymbolCheckerOwnershipTest" --tests "dev.talos.core.index.IndexedWorkspaceSymbolCheckerTest" --tests "dev.talos.cli.modes.PromptClassifierTest" --tests "dev.talos.cli.modes.PromptClassifierExplainTest" --tests "dev.talos.cli.modes.ModeControllerTest" --tests "dev.talos.cli.repl.slash.RouteCommandTest" --no-daemon
```

Result: passed.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `60` current and baselined forbidden imports, `0` new
violations, and `0` stale entries.

## Acceptance Criteria

- `WorkspaceSymbolChecker` lives under `dev.talos.core.index`.
- No source imports `dev.talos.cli.modes.WorkspaceSymbolChecker`.
- The old `IndexedWorkspaceSymbolChecker -> cli.modes.WorkspaceSymbolChecker`
  baseline entry is removed.
- Prompt-classifier and mode-controller tests still pass.
- Indexed symbol-checker tests still pass.
- Architecture scanner passes with baseline count reduced from 61 to 60.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- This burns down one clean ownership edge only.
- The architecture scanner is still import-declaration based and does not catch
  fully qualified forbidden references without imports.
- `SafeLogFormatter` and protected-content policy remain larger, higher-risk
  shared-policy ownership questions.

## Known Follow-Ups

- Continue burning down isolated contract/interface ownership mismatches before
  touching runtime policy behavior.
- Add an explicit scanner enhancement ticket if Talos needs fully qualified
  forbidden reference detection before adopting ArchUnit.
