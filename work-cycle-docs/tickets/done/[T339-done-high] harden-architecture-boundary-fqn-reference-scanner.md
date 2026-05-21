# [T339-done-high] Harden Architecture Boundary FQN Reference Scanner

Status: done
Priority: high
Date: 2026-05-21
Branch: `T334-T340`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`
Predecessor: `[T338-done-medium] move-workspace-symbol-checker-to-core-index-boundary`

## Evidence Summary

- Source: branch review finding after T338.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on
  `T334-T340`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout plus Gradle TestKit fixtures.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: hardened `validateArchitectureBoundaries` to scan
  stripped Java source for fully-qualified forbidden `dev.talos...` type
  references in addition to imports.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused TestKit coverage and real repository scanner
  passed.

## Problem

T336 originally scanned Java `import` declarations only. That meant a forbidden
edge could bypass the architecture ratchet by using a fully-qualified type name
directly in source:

```java
return dev.talos.runtime.policy.SafeLogFormatter.value(input);
```

This was not a runtime bug, but it weakened every future architecture cleanup
because the ratchet could miss new dependencies expressed without imports.

## Goal

Make `validateArchitectureBoundaries` reject forbidden fully-qualified
`dev.talos...` type references without increasing false positives from comments,
string literals, char literals, or Java text blocks.

## Non-Goals

- No ArchUnit dependency.
- No bytecode analysis.
- No Java parser dependency.
- No package-boundary rule expansion.
- No production runtime behavior change.
- No current baseline growth.

## Implementation Summary

Added source preprocessing to `build.gradle.kts`:

- strips line comments;
- strips block comments;
- strips string literals;
- strips char literals;
- strips Java text blocks;
- preserves line breaks enough for readable scan behavior.

Added source reference scanning:

- keeps the existing import scan;
- finds fully-qualified `dev.talos...` token references;
- normalizes method/member references back to the conventional Java type token
  at the first uppercase segment;
- compares both imports and normalized fully-qualified references against the
  same architecture boundary rules.

Updated scanner wording from import-only terminology to source-reference
terminology in the task description, JSON report fields, Markdown report
headings, and baseline header.

## Architecture Metadata

Capability:

- Architecture boundary enforcement.

Operation(s):

- Static source validation hardening.

Owning package/class:

- Gradle build validation task in `build.gradle.kts`.

New or changed tools:

- `validateArchitectureBoundaries` detects forbidden imports and fully-qualified
  forbidden type references.

Risk, approval, and protected paths:

- Risk level: low runtime risk; medium build-gate risk because scanner behavior
  is stricter.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: TestKit fixture proves forbidden FQN references fail and
  comments/strings do not inflate the violation count.
- Verification profile: focused TestKit suite plus real repo
  `validateArchitectureBoundaries`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: scanner implementation and docs.
- Forbidden: package moves, baseline growth, runtime policy changes.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest.rejectsUnbaselinedForbiddenFullyQualifiedReference" --no-daemon
```

Result: failed with unexpected build success because the scanner did not detect
the forbidden fully-qualified reference.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest.rejectsUnbaselinedForbiddenFullyQualifiedReference" --no-daemon
```

Result: passed after adding stripped-source fully-qualified reference scanning.

Focused scanner suite:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest" --no-daemon
```

Result: passed.

Review hardening:

- Added coverage proving block comments, line comments, escaped strings, char
  literals, text blocks, and escaped text-block quote runs do not create false
  boundary violations.
- Added coverage proving static imports normalize to the referenced type rather
  than method/member-level keys.
- Added coverage proving forbidden package wildcard imports remain rejected.
- Renamed JSON evidence fields from import-only names to
  `forbiddenReferencePrefixes` and `referencedSymbol`.

Real repo scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `60` current and baselined forbidden references, `0` new
violations, and `0` stale entries.

## Acceptance Criteria

- A forbidden fully-qualified `dev.talos...` type reference without an import
  fails `validateArchitectureBoundaries`.
- Comments and string/char literals do not create false boundary violations.
- Existing import-based scanner behavior still works.
- The real repository scanner passes with no baseline growth.
- Scanner reports use source-reference wording instead of import-only wording.
- JSON reports use `forbiddenReferencePrefixes` and `referencedSymbol`, not stale
  import-only field names.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The scanner uses source token analysis and Java naming conventions, not a full
  parser. It normalizes to the first uppercase segment in a `dev.talos...`
  reference, including imports and static imports.
- Package wildcard imports, such as `dev.talos.runtime.policy.*`, are preserved
  as wildcard source-reference keys because they do not name a concrete type.
- Lowercase Java type names would not be detected as type references. This is
  acceptable for the current Talos codebase but is not a substitute for
  bytecode or AST dependency analysis.
- Static constants after a type may be normalized to the owning type in common
  cases, but this is still convention-based.

## Known Follow-Ups

- Consider ArchUnit only if source-token scanning starts producing blind spots
  or false positives that block real cleanup work.
- Continue the boundary burn-down with small ownership moves now that the
  ratchet is harder to bypass.
