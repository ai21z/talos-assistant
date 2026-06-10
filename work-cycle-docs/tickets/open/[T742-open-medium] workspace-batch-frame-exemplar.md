# T742 - Workspace-Batch Capability-Frame Exemplar

Status: open
Severity: medium
Release gate: supports T280/T284 bank stability (defense-in-depth with T739)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

The current-turn capability frame for `WORKSPACE_OPERATION_REQUIRED` tells the
model what NOT to do (don't emulate ops via write/edit, don't substitute
similar filenames) but never shows the wire format it must produce. The batch
tool's `operations_json` is described in prose only. Two of three full-bank
failures concentrated on the batch scenario, where qwen produced either a
malformed payload or no call.

## Evidence Analysis

- `runtime/policy/CurrentTurnCapabilityFrame.java:99-104`: the
  WORKSPACE_OPERATION_REQUIRED frame text — prohibitions only, no example.
- Lines 209-213: the expected-targets subsection — again prohibition-only.
- `runtime/workspace/BatchWorkspaceApplyTool.java:36-39`: schema describes
  required keys per op in a prose description string.
- Few-shot protocol exemplars measurably improve tool-format adherence for
  sub-20B models (Qwen function-calling guidance; Berkeley Function-Calling
  Leaderboard findings); cost ≈ 40 tokens within the 8192 context budget.
- Even after T739 grammar enforcement, a grammar can only guarantee "some
  string" for a string-typed field — the exemplar shows the model what that
  string must look like (until T744 lands a native array).

## Architectural Hypothesis

The capability frame is the runtime's voice for current-turn expectations; a
single literal example line is the cheapest in-context correction for the
double-encoded payload task.

## Architecture Metadata

Capability: current-turn capability frame (prompt construction)
Operation(s): workspace batch (and workspace ops generally)
Owning package/class: `dev.talos.runtime.policy.CurrentTurnCapabilityFrame`
New or changed tools: none
Risk, approval, and protected paths: unchanged
Checkpoint, evidence, verification, and repair: unchanged
Outcome and trace:
  - Trace/debug fields: frame hash changes (prompt-audit `currentTurnFrame`
    hash values in existing tests/fixtures may shift)
Refactor scope: CurrentTurnCapabilityFrame + frame tests + any exact-text
frame snapshot assertions that break

## Required Behavior

- Append one literal example line to the WORKSPACE_OPERATION_REQUIRED frame,
  e.g.:
  `operations_json example: [{"op":"copy_path","from":"a.md","to":"b.md"},{"op":"mkdir","path":"docs/reports"}]`
- Keep the existing prohibition lines verbatim.

## Non-Goals

- No schema change (T744).
- No exemplar for non-workspace obligations.

## Tests

- Frame test asserting the literal example is present for
  WORKSPACE_OPERATION_REQUIRED and absent for other obligations.
- Update any existing exact-text frame snapshot assertions that the new line
  breaks (budgeted; expected in CurrentTurnCapabilityFrame tests and possibly
  prompt-audit fixtures).

## Acceptance Criteria

- Focused frame tests green; full `test` lane green (snapshot updates
  included).
- Live prompt-debug for a batch turn shows the exemplar line (T746).
- CHANGELOG `## [Unreleased]` gains a T742 entry.

## 2026-06-10 completion evidence

- Implemented: one literal example line appended to the
  WORKSPACE_OPERATION_REQUIRED frame in `CurrentTurnCapabilityFrame`
  (prohibition lines untouched).
- Tests green: `CurrentTurnCapabilityFrameTest` — example asserted present for
  workspace turns and absent for mutating-tool turns; no snapshot fallout —
  full `test` + `e2eTest` lanes BUILD SUCCESSFUL (2m09s).
- Live prompt-debug confirmation deferred to T746 banks.
