# [T346-done-high] Extract Neutral Sink Safety Primitives

Status: done
Priority: high
Date: 2026-05-21
Branch: `T346`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T345-done-high] policy-and-sink-safety-ownership-decision`

## Evidence Summary

- Source: T345 ownership decision after PR #10 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T346`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: moved sink-safe log formatting and pure redaction/token
  primitives out of `dev.talos.runtime.policy` into neutral
  `dev.talos.safety`.
- Verification status: RED/GREEN ownership test, focused sink-safety tests,
  architecture scanner, runtime sink inventory test, diff hygiene, and full
  `check` passed.

## Problem

`SafeLogFormatter` was a cross-layer sink-safety utility, but it lived under
`dev.talos.runtime.policy`. Core, engine, and tool packages imported it only
to render safe diagnostics. That made the architecture baseline preserve a
false ownership story: lower layers were not depending on runtime orchestration
semantics; they were depending on neutral redaction infrastructure placed in
the wrong package.

The coupling was deeper than the formatter class name:

- `SafeLogFormatter` delegated to `ProtectedContentPolicy`.
- `ProtectedContentPolicy` mixed pure text redaction with runtime/tool-result
  adapter behavior.
- Protected path token recognition was buried inside workspace-aware
  `ProtectedPathPolicy`.

## Goal

Extract neutral sink-safety primitives and move `SafeLogFormatter` out of
`dev.talos.runtime.policy` without changing runtime behavior.

## Non-Goals

- No broad protected-content policy redesign.
- No `ToolResult` adapter move into `dev.talos.safety`.
- No private-mode, protected-read-scope, or RAG policy behavior change.
- No approval, checkpoint, command-profile, or tool execution behavior change.
- No baseline growth.

## Implementation Summary

- Added `dev.talos.safety.ProtectedContentSanitizer` for pure text, canary,
  secret-like assignment, private marker, private-document fact, map, and
  parameter redaction.
- Added `dev.talos.safety.ProtectedPathTokens` for pure protected path-token
  recognition.
- Moved `SafeLogFormatter` to `dev.talos.safety.SafeLogFormatter`.
- Kept `ProtectedContentPolicy` in `dev.talos.runtime.policy` as the
  workspace-aware and tool-result adapter.
- Kept `ProtectedPathPolicy` in `dev.talos.runtime.policy` for workspace and
  tool-call classification, delegating only pure token recognition to
  `ProtectedPathTokens`.
- Updated all `SafeLogFormatter` imports to the neutral package.
- Added a `safety-no-talos-layers` architecture rule so
  `src/main/java/dev/talos/safety/` cannot reference app, CLI, core, engine,
  runtime, SPI, or tools packages.
- Added `SafetyOwnershipTest` to prove the formatter and pure primitives live
  in `dev.talos.safety`, the old runtime formatter no longer exists, and the
  lower-layer call sites no longer import `dev.talos.runtime.policy.SafeLogFormatter`.
- Removed the nine stale `SafeLogFormatter` entries from the architecture
  baseline.
- Updated the runtime sink-safety inventory to name the neutral owner.

## Architecture Metadata

Capability:

- Sink-safe diagnostic formatting and durable-artifact redaction primitives.

Operation(s):

- Static ownership relocation.
- Behavior-preserving package extraction.

Owning package/class:

- `dev.talos.safety.ProtectedContentSanitizer`
- `dev.talos.safety.ProtectedPathTokens`
- `dev.talos.safety.SafeLogFormatter`
- Runtime adapter retained: `dev.talos.runtime.policy.ProtectedContentPolicy`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium. The public call sites still format the same values, but
  the redaction implementation was split across new owner classes.
- Approval behavior: not changed.
- Protected path behavior: not changed.
- Private-mode behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test, sanitizer regression
  tests, protected path parity tests, and real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed except for import owner.

Refactor scope:

- Allowed: extract pure sanitizer primitives and neutral formatter ownership.
- Forbidden: move mixed runtime policy wholesale or reinterpret private
  document/read-scope behavior.

## Baseline Result

Before T346, the architecture baseline had `56` entries after T344 was merged
and T345 was documented.

T346 removed the nine `SafeLogFormatter` package-direction entries by moving
the formatter to a neutral owner:

- `core-no-runtime|src/main/java/dev/talos/core/embed/EmbeddingsClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/index/Indexer.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/index/LuceneStore.java|dev.talos.runtime.policy.SafeLogFormatter`
- `core-no-runtime|src/main/java/dev/talos/core/rag/RagService.java|dev.talos.runtime.policy.SafeLogFormatter`
- `engine-no-runtime|src/main/java/dev/talos/engine/compat/CompatChatClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `engine-no-runtime|src/main/java/dev/talos/engine/ollama/OllamaChatClient.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/ContentVerifier.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/FileEditTool.java|dev.talos.runtime.policy.SafeLogFormatter`
- `tools-no-runtime|src/main/java/dev/talos/tools/impl/FileWriteTool.java|dev.talos.runtime.policy.SafeLogFormatter`

New baseline result:

- Total: `47`
- New violations: `0`
- Stale baseline entries: `0`

The counter reduction is a consequence of the ownership correction, not the
selection metric.

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.SafetyOwnershipTest" --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest.rejectsSafetyPackageReferencesToTalosLayers" --no-daemon
```

Expected and observed: failed before implementation because the safety package
and scanner rule did not exist.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.SafetyOwnershipTest" --tests "dev.talos.build.ArchitectureBoundaryValidationTaskTest.rejectsSafetyPackageReferencesToTalosLayers" --tests "dev.talos.runtime.policy.SensitiveLogRedactionTest" --no-daemon
.\gradlew.bat test --tests "*SafetyOwnershipTest" --tests "*SensitiveLogRedactionTest" --tests "*RuntimeSinkSafetyInventoryTest" --tests "*ProtectedPathPolicyTest" --tests "*ContextItemProtectedPathParityTest" --tests "*ArchitectureBoundaryValidationTaskTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed.

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

Do not continue by moving mixed policy classes wholesale. The remaining
protected-content, private-document, protected-read-scope, command/workspace,
RAG/context, runtime/CLI session, and SPI edges each need their own ownership
decision or interface-inversion ticket.
