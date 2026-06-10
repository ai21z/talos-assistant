# T737 - Approved Private-Document Answer Postcondition

Status: done
Severity: high
Release gate: yes - PTY/manual private-document approval lane truthfulness
Branch: codex/t312-live-workspace-ops
Created/updated: 2026-06-09
Owner: unassigned

## Problem

When a private-document per-turn model handoff is approved, Talos can still
render a blocked/redaction-style visible answer instead of answering the user's
question from the approved current-turn evidence.

The current live PTY packet shows the approved DOCX turn ending with:

```text
[Approval blocked: protected content was redacted from history]
```

even though:

- the per-turn approval was granted;
- the tool call succeeded;
- the model was given redacted extracted evidence for the approved turn; and
- the turn was marked `COMPLETE / READ_ONLY_ANSWERED`.

That is an outcome-truth failure in a release gate lane.

## Evidence

- Live packet:
  `local/manual-testing/t736-pty-manual-20260609-164535/artifacts/session.txt`
- Approval granted:
  `Allow? [y=yes, N=no] y`
- Visible answer failed to answer:
  `[Approval blocked: protected content was redacted from history]`
- `/last trace` still reports `COMPLETE / READ_ONLY_ANSWERED`
- Saved prompt-debug/provider-body show the model received redacted extracted
  evidence:
  `Patient name: [redacted-private-document-canary]`

## Architectural Hypothesis

The runtime postcondition/repair path is keyed to ordinary protected-path reads,
not to approved private-document extracted reads.

Likely fault cluster:

- `ProtectedReadAnswerGuard` only recognizes current approved protected reads
  through `ProtectedPathPolicy` / protected-path heuristics.
- Approved private-document handoff uses a different privacy class and can reach
  the final-answer path without qualifying for current-turn protected-read
  repair.
- A blocked/redacted model answer therefore survives when the runtime should
  replace it with a truthful current-evidence answer or a safe boolean
  containment answer.

## Architecture Metadata

- Capability ownership: runtime outcome shaping for approved private/protected
  reads
- Operation type: read-only answer shaping after approved private-document
  extraction and per-turn handoff
- Risk: false blocked answer after approved read; PTY release evidence remains
  open
- Approval behavior: unchanged; approval must still be required and recorded
- Protected path behavior: no broadening of raw protected-path access
- Checkpoint behavior: not applicable
- Evidence obligation: answer must be grounded in the approved current-turn
  extracted evidence
- Verification profile: deterministic unit/integration/e2e regression plus
  later PTY rerun
- Repair profile: allowed to replace blocked/refusal answers with truthful
  current-evidence answers for approved private-document reads only

## Required Behavior

- After approved private-document handoff, Talos must not present denial or
  redaction-block wording as the visible final answer.
- If the model emits a blocked/refusal/redaction answer despite approved
  current-turn private-document evidence, runtime must repair it to a truthful
  safe answer.
- For containment-style prompts such as
  `whether it contains a patient name. Do not print the name.`,
  Talos may answer yes/no, but must not print the raw private fact.
- Persisted history and trace redaction may still redact approved private
  answers for storage, but visible current-turn output must remain truthful.

## Non-Goals

- No change to approval requirements.
- No change to private-document artifact redaction policy.
- No weakening of prompt-debug/provider-body redaction.
- No broad architecture cleanup outside answer-shaping ownership.

## Tests

- Focused failing regression for approved private-document handoff where the
  scripted model emits blocked/redaction wording instead of answering.
- Guard/unit coverage for private-document postcondition repair if helper logic
  is extracted.
- Focused synchronized or integration test proving:
  - no raw private name leak;
  - visible answer answers the containment question;
  - trace still records approved handoff;
  - persisted history remains redacted as designed.

## Acceptance Criteria

- A deterministic regression reproduces the current approved-turn failure before
  the fix.
- After the fix, the same regression passes with a truthful safe answer.
- No raw private-document fact appears in the visible answer or persisted
  artifacts.
- Existing denied-lane wording and private-document artifact redaction tests
  remain green.
- `T306` / `T312` remain open until a fresh PTY rerun proves the fixed lane in
  live evidence.

## Completion Evidence

Implemented:

- widened approved-read postcondition detection so successful private-document
  extraction reads qualify for current-turn protected-read repair
- broadened refusal/blocked-answer detection to catch
  `approval blocked` / `redacted from history` shapes
- added containment-style safe answer repair for prompts such as
  `whether it contains a patient name. Do not print the name.`
- passed the actual request text into the approved-read postcondition layer so
  repair can use the real current prompt rather than tool-result pseudo-user
  messages

Deterministic regression added:

- `ExecutionOutcomeTest.approvedPrivateDocumentContainmentPromptRepairsBlockedHistoryAnswer`

Focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.approvedPrivateDocumentContainmentPromptRepairsBlockedHistoryAnswer" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.ProtectedReadAnswerGuardTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

Notes:

- `git diff --check` reported only CRLF normalization warnings, no whitespace
  errors.
- This ticket does not close the PTY/manual release gate by itself. Fresh human
  PTY evidence is still required under `T306` / `T312`.
