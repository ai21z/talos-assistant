# [T336-done-high] Architecture Boundary Ratchet And Import Scanner

Status: done
Priority: high
Date: 2026-05-21
Branch: `v0.9.0-beta-dev`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`

## Evidence Summary

- Source: T335 architecture hygiene baseline follow-up.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on
  `v0.9.0-beta-dev`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout plus Gradle TestKit fixtures.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: build validation task, architecture baseline file, and
  build-task tests.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused tests and scanner task passed.

## Problem

T335 proved package-direction debt, but documentation alone cannot stop the
next ticket from adding another forbidden edge. Talos needs a ratchet before
large dependency-injection or policy-extraction work begins.

## Goal

Add a source-level architecture boundary scanner that:

- detects selected forbidden package imports;
- compares them against a checked-in baseline;
- fails on any new forbidden import;
- fails when a baseline entry goes stale after debt is removed;
- writes local JSON and Markdown reports for reviewers;
- runs as part of Gradle `check`.

## Non-Goals

- No production package movement.
- No behavior change.
- No DI framework.
- No ArchUnit dependency yet.
- No attempt to solve all package cycles in one pass.
- No generated report commit from `build/reports`.

## Implementation Summary

Added `validateArchitectureBoundaries` to `build.gradle.kts`.

The task scans `src/main/java` imports for these ratcheted rules:

- `runtime-core-no-cli`: `runtime` and `core` must not import `cli`.
- `core-no-runtime`: `core` must not import `runtime`.
- `tools-no-runtime`: `tools` must not import `runtime`.
- `engine-no-runtime`: `engine` must not import `runtime`.
- `spi-no-upper-layers`: `spi` must not import `cli`, `core`, `runtime`, or
  `tools`.

Added baseline:

- `config/architecture-boundary-baseline.txt`

Current baseline size:

```text
62 forbidden import edges
```

Generated local reports when the task runs:

```text
build/reports/talos/architecture-boundaries.json
build/reports/talos/architecture-boundaries.md
```

Added focused TestKit coverage:

- `src/test/java/dev/talos/build/ArchitectureBoundaryValidationTaskTest.java`

## Architecture Metadata

Capability:

- Architecture boundary enforcement.

Operation(s):

- Static source validation.

Owning package/class:

- Gradle build validation task in `build.gradle.kts`.

New or changed tools:

- `validateArchitectureBoundaries` Gradle task.

Risk, approval, and protected paths:

- Risk level: low runtime risk, high architecture governance value.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: build report with current, new, and stale boundary
  entries.
- Verification profile: Gradle static import scan.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: build validation and baseline.
- Forbidden: production behavior changes and package moves.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest" --no-daemon
```

Result: failed because `validateArchitectureBoundaries` did not exist.

Additional RED:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest.treatsMissingBaselineAsEmptyBaseline" --no-daemon
```

Result: failed because a missing baseline file was treated as a Gradle input
configuration error instead of an empty baseline.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest" --no-daemon
```

Result: passed.

Real repo scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed after baselining the 62 current violations.

## Acceptance Criteria

- `validateArchitectureBoundaries` exists.
- Task writes JSON and Markdown reports.
- Task detects forbidden imports.
- Task accepts exactly baselined current debt.
- Task fails new forbidden imports.
- Task fails stale baseline entries.
- Task treats a missing baseline file as empty.
- Task is wired into `check`.
- Current repo passes with the checked-in baseline.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- This is a source import scanner, not bytecode dependency analysis.
- It only scans Java `import` declarations. Fully qualified forbidden type
  references without imports are not caught yet; add token/AST scanning or
  ArchUnit before claiming complete dependency analysis.
- It intentionally covers the highest-value T335 edges, not every possible
  package relation.
- Current debt is accepted only as a baseline; follow-up tickets must burn it
  down, not add more entries casually.

## Known Follow-Ups

- Runtime/core CLI dependency split.
- Move shared safe logging and protected-content policy out of runtime where
  lower layers need it.
- Split tool API from runtime-owned execution policy.
- Decide whether a later ArchUnit dependency is worth the extra build surface
  after this lightweight ratchet proves useful.
