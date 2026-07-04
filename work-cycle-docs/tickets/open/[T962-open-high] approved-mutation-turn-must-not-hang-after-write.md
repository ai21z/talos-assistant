# [T962-open-high] Approved Mutation Turn Must Not Hang After Write

Status: open
Priority: high

## Evidence Summary

- Source: installed-product manual PTY release-confidence audit
- Date: 2026-07-04/05 Europe/Madrid
- Talos version / commit: 0.10.8 / 3369b237b297320915dad9dc25aa70769b2a4027
- Installed executable: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Model/backend: `llama_cpp/qwen2.5-coder-14b`
- Workspace fixture: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-workspaces\qwen`
- Audit report: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\RELEASE-CONFIDENCE-SUMMARY.md`
- Raw transcript path: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\qwen\PTY-TRANSCRIPT-QWEN.txt` (known incomplete for child PTY output)
- Final file state: `C:\Users\arisz\Desktop\testtalos\release-confidence-0.10.8-20260704-233706\manual-testing\qwen\final-file-state.txt`
- Approval choices: `y` approve once for `talos.edit_file`
- Verification status: unknown; the turn did not return to prompt, so `/last trace` could not be captured for the edit turn

Redacted prompt sequence:

```text
/mode agent
Make script.js fix the selector bug by changing .missing-button to .cta-button. Only edit script.js.
<approve once with y>
```

Expected behavior:

```text
After approval, Talos applies the edit, runs the relevant verification path,
prints a final outcome, preserves a trace, and returns to the REPL prompt within
a bounded timeout. If the model/backend stalls after mutation, Talos reports a
truthful partial/failure outcome and preserves trace evidence.
```

Observed behavior:

```text
The approval window was correct and the file mutation landed: script.js changed
from .missing-button to .cta-button and scripts.js stayed unchanged. Talos did
not return a final answer or prompt after several minutes, and the PTY session
had to be interrupted. No last trace could be captured for the mutation turn.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `REPAIR_CONTROL`
- `VERIFICATION`
- `TRACE_REDACTION`
- `MODEL_COMPETENCE`

Blocker level:

- release blocker

Why this level:

```text
An approved mutation that changes disk state but never returns an outcome or
trace is not release-grade. The user is left with a changed workspace and no
trustworthy final state from Talos.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make Qwen answer faster.
```

Architectural hypothesis:

```text
The post-tool continuation/finalization path can stall after a successful
mutation. The runtime needs a bounded post-mutation finalization timeout and a
failure-dominant outcome path that records the mutation and trace even when the
model does not produce a final answer.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ToolCallLoop*`
- `src/main/java/dev/talos/runtime/trace`
- `src/main/java/dev/talos/runtime/verification`
- PTY/manual audit harness and synchronized approval tests

Why a one-off patch is insufficient:

```text
The invariant applies to every mutating tool and every model/backend. Once disk
state changes, Talos must always return a bounded, evidence-backed outcome even
if the model stalls, streams malformed output, or never summarizes.
```

## Goal

```text
Approved mutation turns must not hang indefinitely after a write/edit. Talos
must either complete normally or emit a bounded partial/failure outcome with
trace evidence and the final workspace mutation state.
```

## Non-Goals

- No model-specific timeout only for Qwen.
- No hiding successful mutations as if nothing happened.
- No bypassing post-mutation verification when verification is available.
- No weakening approval or checkpoint policy.

## Implementation Notes

Start by reproducing the failure in a deterministic harness if possible:

- Simulate a model/backend that emits a valid mutating tool call, receives a
  successful tool result, then never emits a final answer.
- Assert that Talos exits the turn with a bounded outcome, trace, and clear
  final answer.
- Ensure the trace includes the successful mutation, checkpoint status, and
  verification/finalization timeout reason.

If this is not deterministic with current test harnesses, add a PTY/manual
evidence ticket hook first, but do not close this ticket from manual observation
alone.

## Architecture Metadata

Capability:

- Agent-mode approved mutation lifecycle.

Operation(s):

- `edit`
- `write`
- `verify`
- `trace`

Owning package/class:

- Assistant turn execution and tool-loop finalization.

New or changed tools:

- None expected.

Risk, approval, and protected paths:

- Risk level: high, because disk state changes before the hang.
- Approval behavior: unchanged; approved mutation must still be recorded.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: if checkpoint was required/created, trace must preserve
  it even on finalization timeout.
- Evidence obligation: final state and trace must identify applied mutation.
- Verification profile: run if possible; otherwise report verification not
  completed due to finalization timeout.
- Repair profile: no automatic extra mutation after timeout.

Outcome and trace:

- Outcome/truth warnings: final answer must say mutation may have applied and
  verification/final answer did not complete, not claim clean success.
- Trace/debug fields: add or assert a timeout/finalization failure field.

Refactor scope:

- Allowed: narrow timeout/finalization seam in the tool loop.
- Forbidden: broad rewrite of model streaming, approval policy, or verification
  architecture.

## Acceptance Criteria

- Deterministic test proves a post-tool model stall after successful mutation
  returns a bounded outcome.
- Trace survives and includes the successful mutation, checkpoint status, and
  finalization timeout/failure reason.
- User-facing output is failure-dominant and does not claim clean success.
- Installed-product Qwen lane is rerun on the same selector-edit prompt and
  returns to prompt with `/last trace` available.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Integration/executor test: model emits `talos.edit_file`, tool succeeds,
  model continuation stalls/times out, runtime returns bounded partial/failure.
- Trace assertion: mutation and timeout reason are present.
- PTY audit rerun: Qwen selector edit with approval once.

Manual/TalosBench rerun:

- Prompt family: Agent static-web edit after approval.
- Workspace fixture: `script.js` and sibling `scripts.js`.
- Expected trace: `read_file`/`edit_file`, checkpoint if required, bounded final
  outcome, no sibling mutation.
- Expected outcome: completed verified or explicit partial/failure, never hang.

Commands:

```powershell
.\gradlew.bat test --tests "*ToolCallLoop*" --tests "*AssistantTurnExecutor*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Treat as release-blocking until a deterministic repro is either fixed or
  disproven by a clean rerun with complete trace evidence.
- Behavior-changing closeout requires a CHANGELOG entry under `## [Unreleased]`.

## Known Risks

- Too-short timeouts could interrupt slow but healthy local model runs. The
  timeout should apply to post-mutation finalization carefully and report the
  exact phase.

## Known Follow-Ups

- If the failure is traced to model/server liveness rather than Talos
  finalization, add a backend-specific health/timeout ticket and keep this
  ticket focused on truthful bounded outcome.
