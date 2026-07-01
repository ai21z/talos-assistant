# [T337-done-medium] Move Tool Alias Policy To Tools Boundary

Status: done
Priority: medium
Date: 2026-05-21
Branch: `v0.9.0-beta-dev`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`
Predecessor: `[T336-done-high] architecture-boundary-ratchet-and-import-scanner`

## Evidence Summary

- Source: T335/T336 architecture hygiene sequence.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on
  `v0.9.0-beta-dev`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: moved tool alias contract types from runtime tool-call
  package to tools package, updated imports, and reduced the architecture
  boundary baseline.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused tests and architecture scanner passed.

## Problem

T336 installed a boundary ratchet with 62 accepted forbidden import edges. One
of those edges was a clean ownership mismatch:

```text
tools-no-runtime|src/main/java/dev/talos/tools/ToolRegistry.java|dev.talos.runtime.toolcall.ToolAliasPolicy
```

`ToolAliasPolicy` is not inherently a runtime loop policy. It defines canonical
tool names and accepted backend/model aliases used by the tool registry and
runtime. Keeping it under `runtime.toolcall` forced the `tools` package to
depend on runtime.

## Goal

Move tool-name alias contracts to the tools package and remove the old
`tools -> runtime.toolcall.ToolAliasPolicy` baseline entry without changing
alias behavior.

## Non-Goals

- No broader tool/runtime package split.
- No alias behavior change.
- No `SafeLogFormatter` or protected-content policy move in this ticket.
- No DI framework.
- No runtime behavior change.

## Implementation Summary

Moved:

- `src/main/java/dev/talos/runtime/toolcall/ToolAliasPolicy.java`
  -> `src/main/java/dev/talos/tools/ToolAliasPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/BackendToolProfile.java`
  -> `src/main/java/dev/talos/tools/BackendToolProfile.java`

Updated imports across runtime, CLI, and tools.

Updated:

- `config/architecture-boundary-baseline.txt`

Architecture baseline count changed:

```text
Before: 62 forbidden import edges
After:  61 forbidden import edges
```

## Architecture Metadata

Capability:

- Tool alias metadata ownership.

Operation(s):

- Behavior-preserving package move.
- Static boundary debt reduction.

Owning package/class:

- `dev.talos.tools.ToolAliasPolicy`
- `dev.talos.tools.BackendToolProfile`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low runtime risk; medium compile/import blast radius.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: architecture scanner must show one fewer baselined
  forbidden edge and no new/stale drift.
- Verification profile: focused unit tests plus `validateArchitectureBoundaries`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: move the alias policy and backend profile enum.
- Forbidden: changing alias tables or tool execution semantics.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolAliasPolicyOwnershipTest" --no-daemon
```

Result: failed to compile because `ToolAliasPolicy` and `BackendToolProfile`
did not exist under `dev.talos.tools`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolAliasPolicyOwnershipTest" --no-daemon
```

Result: passed after the move.

Focused behavior checks:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.ToolRegistryTest" --tests "dev.talos.runtime.toolcall.ToolCallSupportTest" --tests "dev.talos.runtime.TurnProcessorTest" --no-daemon
```

Result: passed.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `61` current and baselined forbidden imports, `0` new
violations, and `0` stale entries.

## Acceptance Criteria

- `ToolAliasPolicy` lives under `dev.talos.tools`.
- `BackendToolProfile` lives under `dev.talos.tools`.
- No source imports `dev.talos.runtime.toolcall.ToolAliasPolicy` or
  `dev.talos.runtime.toolcall.BackendToolProfile`.
- The old `ToolRegistry -> runtime.toolcall.ToolAliasPolicy` baseline entry is
  removed.
- Tool alias behavior remains covered.
- Architecture scanner passes with baseline count reduced from 62 to 61.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- This burns down only one boundary edge. It is useful because it proves the
  ratchet can move downward, but it does not solve the larger runtime/tools
  cycle.
- `SafeLogFormatter` remains a larger and less clean move because it depends on
  protected-content policy still owned by runtime.

## Known Follow-Ups

- Continue burning down the simplest tool/runtime edges before touching
  high-risk runtime policy.
- Consider a future dedicated ticket for moving shared redaction/path-safety
  primitives only after protected-content ownership is mapped.
