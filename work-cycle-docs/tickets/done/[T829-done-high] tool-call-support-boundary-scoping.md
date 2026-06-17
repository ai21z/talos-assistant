# [T829-done-high] ToolCallSupport Boundary Scoping

Status: done
Priority: high
Date: 2026-06-17
Branch: `v0.9.0-beta-dev`
Candidate version: `0.10.5`
Predecessor: `[T828-done-high] tool-call-execution-stage-guard-chain-extraction`

## Why This Ticket Exists

T828 reduced `ToolCallExecutionStage` hotspot pressure by extracting the
pre-execution guard chain. The remaining `runtime.toolcall` internals still
include a broad `ToolCallSupport` helper surface with native-call conversion,
tool-result formatting, retry/user-request extraction, path/call repair, and
compaction utilities.

T829 records current evidence and pins the observable helper boundaries before
any T830 production extraction. T829 is scoping/characterization only and does
not authorize moving production code.

## Scope

In scope:

- Add a T829 scoping report for `ToolCallSupport`.
- Add direct `ToolCallSupport` boundary characterization coverage.
- Validate the candidate seam hypotheses:
  native-call conversion, result formatting, retry/request extraction,
  path/call repair, and compaction.
- Record current generated priority evidence and deferred alternatives.

Out of scope:

- No production source changes.
- No `ToolCallLoop` public static delegate changes.
- No `LoopState` hardening.
- No `ExecutionOutcome` relocation.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.
- No `site/`, `.gitignore`, or `.external assistant/` staging.

## Acceptance Criteria

- `ToolCallSupportBoundaryCharacterizationTest` passes.
- Existing `ToolCallSupportTest`, `ToolCallLoopCompactionTest`,
  `NativeToolPipelineTest`, and `runtime.toolcall.*` suites pass.
- Full `check` passes.
- `wikiEvidenceCloseGate --rerun-tasks` passes.
- T829 report states that seams are hypotheses and T830 must choose one
  production extraction after review.

## Completion Evidence

Completed as scoping/characterization only.

- `ToolCallSupportBoundaryCharacterizationTest`: `6` tests, `0` failures,
  `0` errors.
- Full `check` passed.
- `wikiEvidenceCloseGate --rerun-tasks` passed.
- Generated architecture evidence was reviewed at
  `4104a90c6a9736997b13aa8736a3be2db68c7a17`.
- T829 added no production source changes and did not authorize extraction by
  itself.
- T830 selected the native-call conversion seam as the first production
  `ToolCallSupport` extraction because it is a narrow data-transform boundary
  already covered by helper-level characterization.
