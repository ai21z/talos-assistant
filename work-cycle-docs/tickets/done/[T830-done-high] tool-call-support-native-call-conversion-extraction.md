# [T830-done-high] ToolCallSupport Native-Call Conversion Extraction

Status: done
Priority: high
Date: 2026-06-17
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T829-done-high] tool-call-support-boundary-scoping`

## Why This Ticket Exists

T829 scoped the broad `ToolCallSupport` helper surface and selected native-call
conversion as the safest first production extraction. The seam is a narrow data
transformation: provider-native calls become Talos `ToolCall` values while
preserving legacy scalar rendering and JSON rendering for container-valued
arguments.

T830 extracts that conversion into a package-private collaborator while keeping
the existing public/static compatibility delegates stable.

## Scope

In scope:

- Add package-private `NativeToolCallConverter` in
  `dev.talos.runtime.toolcall`.
- Move only native-call conversion, native argument JSON serialization, and
  scalar argument rendering from `ToolCallSupport` into that collaborator.
- Keep `ToolCallSupport.convertNativeToolCalls(...)` as the public static
  delegate.
- Keep `ToolCallLoop.convertNativeToolCalls(...)` and `ToolCallParseStage`
  behavior unchanged.
- Add focused collaborator tests.

Out of scope:

- No result formatting extraction.
- No retry/request extraction.
- No path/call repair extraction.
- No compaction extraction.
- No `LoopState`, stage, or `ExecutionOutcome` move.
- No trust-surface redaction movement.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.
- No `site/`, `.gitignore`, or `.claude/` staging.

## Acceptance Criteria

- Container-valued native arguments still serialize as JSON.
- Scalar native arguments still use legacy `String.valueOf(...)`.
- Null native argument maps still produce empty parameters.
- JSON serialization failures still fall back to `String.valueOf(...)`.
- `ToolCallSupport.convertNativeToolCalls(...)` remains callable.
- `ToolCallLoop.convertNativeToolCalls(...)` remains callable.
- `ToolCallSupportBoundaryCharacterizationTest`, `ToolCallSupportTest`,
  `NativeToolPipelineTest`, `ToolCallLoopNativeTest`, `runtime.toolcall.*`,
  `ToolCallLoop*`, full `check`, and `wikiEvidenceCloseGate --rerun-tasks`
  pass.

## Completion Evidence

- Implementation commit:
  `496799a46ca131a0d8164e49e2a6be130efe6e69`.
- Added package-private `NativeToolCallConverter` and kept
  `ToolCallSupport.convertNativeToolCalls(...)` plus the `ToolCallLoop`
  delegate stable.
- Preserved container-argument JSON rendering, legacy scalar
  `String.valueOf(...)`, null-argument empty parameters, and serialization
  fallback behavior.
- Verified with focused native/tool-loop guard suites, full `check`,
  `wikiEvidenceCloseGate --rerun-tasks`, source hygiene, and architecture
  evidence anchored to the implementation commit.
- Result formatting, retry/request extraction, path/call repair, compaction,
  stages, and trust-surface redaction remain deferred. T831 is the next
  result-formatting extraction ticket.
