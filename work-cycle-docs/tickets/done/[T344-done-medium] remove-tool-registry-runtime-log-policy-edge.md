# [T344-done-medium] Remove Tool Registry Runtime Log Policy Edge

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T344`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T343-done-medium] remove-conversation-compactor-runtime-log-policy-edge`

## Evidence Summary

- Source: post-T343 architecture burn-down request after PR #8 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T344`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Baseline review: the remaining `57` entries were inspected before selecting
  this ticket. The remainder is mixed-risk, not uniformly cheap.
- File diff summary: removed one `tools-no-runtime` baseline edge by replacing
  tool alias and fuzzy-match debug logs' runtime policy formatter dependency
  with content-free diagnostics.
- Verification status: RED/GREEN ownership test, focused tool registry tests,
  redaction inventory update, architecture scanner, diff hygiene, and full
  `check` passed.

## Problem

`ToolRegistry` lives in `dev.talos.tools`, but its alias and fuzzy-match debug
logging imported `dev.talos.runtime.policy.SafeLogFormatter` only to render
requested tool names and canonical tool names in debug diagnostics.

That created a tools-to-runtime dependency for nonessential diagnostic detail.
Tool registration, alias resolution, fuzzy matching, and canonicalization do
not need runtime protected-content policy ownership.

## Goal

Remove the `ToolRegistry -> SafeLogFormatter` boundary edge without changing
tool resolution, alias behavior, fuzzy matching behavior, approval behavior, or
runtime policy ownership.

## Non-Goals

- No `SafeLogFormatter` package move.
- No `ProtectedContentPolicy` package move.
- No tool alias behavior change.
- No fuzzy matching behavior change.
- No tool permission, approval, or execution behavior change.
- No baseline growth.
- No broad logging-policy redesign.

## Implementation Summary

- Added an ownership regression test proving `ToolRegistry` does not reference
  `dev.talos.runtime.policy.SafeLogFormatter` in source or in the architecture
  baseline.
- Removed the `SafeLogFormatter` import from `ToolRegistry`.
- Replaced alias, fuzzy-match, and case-normalization debug logs with
  content-free diagnostics.
- Updated the redaction source-inventory test so these call sites are treated
  as safe because they no longer log user-controlled tool name values at all.
- Removed the matching baseline entry from
  `config/architecture-boundary-baseline.txt`.

## Architecture Metadata

Capability:

- Tool registry lookup, alias resolution, and fuzzy-name normalization.

Operation(s):

- Static ownership boundary cleanup.

Owning package/class:

- `dev.talos.tools.ToolRegistry`.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The only runtime behavior changed is debug log text emitted
  during alias, fuzzy-match, and case-normalized tool lookup paths.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus the real repository
  architecture scanner.
- Verification profile: focused tool registry tests, architecture validation,
  diff checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: remove one tools-package runtime-policy logging edge.
- Forbidden: move runtime policy classes or change tool lookup semantics.

## Baseline Evaluation

Before starting T344, the architecture baseline had `57` entries:

- `core-no-runtime`: `17`
- `engine-no-runtime`: `2`
- `runtime-core-no-cli`: `15`
- `spi-no-upper-layers`: `4`
- `tools-no-runtime`: `19`

The highest-repeat forbidden references were:

- `SafeLogFormatter`: `10`
- `ProtectedContentPolicy`: `6`
- `cli.repl.Result`: `5`
- `cli.repl.SessionMemory`: `4`
- `cli.repl.Context`: `3`
- `PrivateDocumentPolicy`: `3`
- `ProtectedReadScopePolicy`: `2`

Conclusion: the remaining baseline is not cheap enough to burn down blindly.
The current rhythm should continue only for isolated ownership leaks where the
edge is diagnostic-only or contract-local. Policy semantics, runtime-to-CLI
session coupling, RAG/indexing privacy, and command execution edges need
separate design review before movement.

T344 selected `ToolRegistry -> SafeLogFormatter` because it was a
diagnostics-only edge inside tool-name lookup, and it could be removed without
changing runtime behavior.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolAliasPolicyOwnershipTest.toolRegistryDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: failed because `ToolRegistry` and the architecture baseline still
referenced `dev.talos.runtime.policy.SafeLogFormatter`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolAliasPolicyOwnershipTest.toolRegistryDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: passed after removing the runtime-policy formatter import, replacing
the tool-name-bearing debug logs with content-free diagnostics, and removing
the baseline entry.

Focused behavior and inventory coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolAliasPolicyOwnershipTest" --tests "dev.talos.tools.ToolRegistryTest" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest.high_risk_user_controlled_log_values_are_safely_handled" --no-daemon
```

Result: passed.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `56` current and baselined forbidden references, `0` new
violations, and `0` stale entries.

Full check:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Acceptance Criteria

- `ToolRegistry` no longer references
  `dev.talos.runtime.policy.SafeLogFormatter`.
- The matching baseline entry is removed.
- `validateArchitectureBoundaries` passes with no new or stale violations.
- Focused tool registry behavior tests pass.
- The redaction source inventory accepts the content-free tool lookup debug
  logs.
- Full `check` passes.
- No generated audit artifacts are committed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The affected debug logs no longer include requested or canonical tool names.
  This is intentional because tool names are user-controlled values and are not
  needed to prove lookup behavior.
- The remaining baseline contains several higher-risk ownership decisions. They
  should not be treated as mechanical one-line removals.

## Known Follow-Ups

- Mark the T344 PR ready only after draft PR CI is visible and clean.
- Continue one-edge burn-down only for remaining isolated, low-risk edges.
- Reconsider `SafeLogFormatter` ownership only after deciding where
  `ProtectedContentPolicy` belongs.
