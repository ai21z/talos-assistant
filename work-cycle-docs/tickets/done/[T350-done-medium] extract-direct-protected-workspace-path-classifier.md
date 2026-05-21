# [T350-done-medium] Extract Direct Protected Workspace Path Classifier

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T350`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T349-done-high] protected-path-and-private-document-policy-boundary-decision`

## Evidence Summary

- Source: T349 ownership decision after PR #14 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Base branch: `origin/v0.9.0-beta-dev` at
  `183268a7c2a808f2926c130a72e3d90ff616aa13`.
- Beta push CI: run `#38`, `Beta Dev CI`, push event for `183268a7`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T350`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: added a neutral direct workspace protected-path
  classifier, made runtime path policy delegate direct path classification to
  it, and migrated `RetrieveTool` away from runtime `ProtectedContentPolicy`.
- Verification status: RED/GREEN ownership and parity tests, focused
  safety/runtime/retrieve tests, architecture scanner, release ledger
  validation, diff hygiene, and full `check` passed.

## Problem

T346 through T348 moved pure sink-safety redaction into `dev.talos.safety`.
T349 decided the next real boundary problem: direct workspace protected-path
classification was still trapped behind runtime policy.

`RetrieveTool` imported `dev.talos.runtime.policy.ProtectedContentPolicy` only
to:

- decide whether a prepared snippet path is protected; and
- sanitize snippet text before returning retrieval output.

That is not runtime approval policy. The tool does not need tool-result
sanitization, approved protected-read scope, private-mode mutation, or
tool-call resource classification for those two operations.

At the same time, runtime `ProtectedPathPolicy` still correctly owns tool-call
path extraction and `ResourceDecision` adaptation. T350 must split direct
workspace path classification without moving tool-call policy downward.

## Goal

Extract direct workspace protected-path classification into neutral safety
ownership and migrate the cleanest adopter, `RetrieveTool`, without changing
private-mode, protected-read-scope, RAG/indexing, or command/workspace
behavior.

## Non-Goals

- No `PrivateDocumentPolicy` move.
- No `ProtectedReadScopePolicy` move.
- No `GrepTool` migration.
- No `RagService` migration.
- No `Indexer` metadata or privacy-policy-version change.
- No runtime-to-CLI boundary work.
- No command/workspace contract work.
- No SPI purity work.
- No baseline growth.

## Implementation Summary

- Added `dev.talos.safety.ProtectedWorkspacePaths`.
- Added a safety parity test proving direct classifier output matches current
  `ProtectedPathPolicy.classify(workspace, rawPath)` behavior for representative
  protected, normal, escaped, control-plane, and whitespace-normalized paths.
- Added a concrete path helper test for protected snippets inside and outside
  the workspace.
- Updated `SafetyOwnershipTest` to require
  `ProtectedWorkspacePaths.java` under `dev.talos.safety`.
- Replaced `ProtectedPathPolicy.classify(Path, String)` implementation with an
  adapter from `ProtectedWorkspacePaths.Decision` to runtime `ResourceDecision`.
- Left `ProtectedPathPolicy.classify(Path, ToolCall)` and
  `classifyAll(Path, ToolCall)` in runtime, where tool-call resource
  classification belongs.
- Updated `RetrieveTool` to use:
  - `ProtectedWorkspacePaths.isProtectedPath(...)`;
  - `ProtectedContentSanitizer.sanitizeText(...)`.
- Removed only the stale `RetrieveTool -> ProtectedContentPolicy` baseline
  entry.

## Architecture Metadata

Capability:

- Protected workspace path classification for direct path inputs.
- Retrieval output path omission and text redaction.

Operation(s):

- Static ownership cleanup.
- Behavior-preserving package extraction.
- One architecture baseline reduction.

Owning package/class:

- Direct workspace path classifier:
  `dev.talos.safety.ProtectedWorkspacePaths`
- Runtime tool-call resource adapter:
  `dev.talos.runtime.policy.ProtectedPathPolicy`
- Retrieval output adapter:
  `dev.talos.tools.impl.RetrieveTool`

New or changed tools:

- `talos.retrieve` implementation dependencies changed, but tool behavior and
  descriptor are unchanged.

Risk, approval, and protected paths:

- Risk level: medium. Path classification is safety-sensitive, so T350 uses
  parity tests against the existing runtime behavior.
- Approval behavior: not changed.
- Protected path behavior: intended to be unchanged for existing direct path
  cases.
- Private-mode behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: RED/GREEN ownership test, direct path parity test,
  focused retrieve/runtime policy tests, and real architecture scanner output.
- Verification profile: focused tests, `validateArchitectureBoundaries`, diff
  checks, release ledger validation, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: split direct path classification below runtime and migrate
  `RetrieveTool` off the runtime protected-content facade.
- Forbidden: move private document policy, protected-read scope, RAG/indexing
  privacy semantics, tool-call classification, command policy, or CLI/runtime
  contracts.

## Baseline Result

Before T350, the architecture baseline had `45` entries after T349 merged.

T350 removes:

- `tools-no-runtime|src/main/java/dev/talos/tools/impl/RetrieveTool.java|dev.talos.runtime.policy.ProtectedContentPolicy`

New baseline result:

- Total: `44`
- New violations: `0`
- Stale baseline entries: `0`

## Verification

RED evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.ProtectedWorkspacePathsTest" --tests "dev.talos.safety.SafetyOwnershipTest.sinkSafetyPackageOwnsSafeLogFormatterAndPurePrimitives" --tests "dev.talos.tools.impl.RetrieveToolTest.retrieve_uses_neutral_safety_for_path_omission_and_text_redaction" --no-daemon
```

Expected and observed: failed before implementation because
`ProtectedWorkspacePaths` did not exist.

Focused GREEN evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.ProtectedWorkspacePathsTest" --tests "dev.talos.safety.SafetyOwnershipTest.sinkSafetyPackageOwnsSafeLogFormatterAndPurePrimitives" --tests "dev.talos.tools.impl.RetrieveToolTest.retrieve_uses_neutral_safety_for_path_omission_and_text_redaction" --no-daemon
.\gradlew.bat test --tests "dev.talos.safety.*" --tests "dev.talos.runtime.policy.ProtectedPathPolicyTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Observed: passed. The architecture report showed `violationCount=44`,
`baselineCount=44`, `newViolationCount=0`, and `staleBaselineCount=0`.

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

Do not mechanically continue into `GrepTool`, `RagService`, or `Indexer`.
Those remaining edges involve private-mode search withholding, protected-read
scope, RAG/indexing privacy, and index metadata. The next implementation
ticket should be chosen from the T349 classification, with tests first and a
single ownership target.
