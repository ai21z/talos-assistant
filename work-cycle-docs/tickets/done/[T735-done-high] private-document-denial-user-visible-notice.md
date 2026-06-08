# T735 - Private Document Denial User-Visible Notice

Status: done
Severity: high
Release gate: yes - T306/T312 PTY/manual evidence reliability
Branch: codex/t312-live-workspace-ops
Created/updated: 2026-06-08
Owner: unassigned

## Problem

The current PTY manual-audit findings show that private-document privacy behavior
is correct, but the completed transcript can still fail the strict validator.
Talos builds the exact deterministic phrase `withheld from model context` as the
model-facing tool result after private-document handoff denial, but the
user-visible final answer can be a model paraphrase that omits that phrase.

This is not a privacy leak. It is an evidence-surface and UX issue: the user and
manual validator need deterministic runtime wording for a privacy boundary, not
model-authored paraphrase.

## Evidence

- `ToolResultModelContextHandoff.privateContentWithheldResult(...)` already
  renders `Private document content was read locally but withheld from model
  context by privacy policy...`, but only as the tool result fed back into the
  loop.
- `ToolLoopFinalAnswerFinalizer.finalizeAnswer(...)` only sanitizes the final
  model answer when content was withheld; it does not add a runtime-owned notice.
- `SynchronizedCliPtyManualAuditValidator` requires the completed transcript to
  contain `private document content was withheld` or `withheld from model
  context`.
- `work-cycle-docs/research/opus-0.10.0-pty-manual-audit-findings.md` records a
  live PTY run where Qwen paraphrased the denial as protected-content denial
  wording, leaving the validator dependent on model phrasing.

## Architecture Metadata

- Capability ownership: runtime tool-call privacy handoff and CLI final-answer
  rendering.
- Operation type: read-only private document extraction with denied
  send-to-model handoff.
- Risk: privacy/audit evidence ambiguity; no raw content leak observed.
- Approval behavior: preserve existing private-document per-turn approval gate.
- Protected path behavior: no path broadening; notices must use sanitized
  targets only.
- Checkpoint behavior: not applicable; no mutation.
- Evidence obligation: transcript must contain deterministic runtime privacy
  notice independent of model wording.
- Verification profile: deterministic unit/e2e tests plus manual PTY validation.
- Repair profile: not applicable.
- Outcome/trace changes: no trace schema change required; final answer gains a
  runtime-owned privacy notice.
- Allowed refactor scope: limited to model-handoff decision metadata, loop state,
  final answer finalization, and focused tests.

## Required Behavior

- Private-document handoff denial/local-display-only must render a deterministic
  user-visible line containing `withheld from model context`.
- The notice must not include raw extracted document facts, protected values, or
  full sensitive paths.
- Approved private-document handoff must not render the denial notice.
- Existing model-context redaction and artifact redaction behavior must remain.
- The manual PTY validator should remain strict; do not relax it as the primary
  fix.

## Tests

- `ToolResultModelContextHandoffTest`: private-document denial exposes a
  sanitized user-visible notice; approval path does not.
- `ToolLoopFinalAnswerFinalizerTest`: a model paraphrase still gets the runtime
  privacy notice; private canaries are redacted; duplicate notices are not
  repeated.
- `SynchronizedApprovalAuditRunnerTest`: private DOCX denial with paraphrased
  model answer still returns final answer containing `withheld from model
  context` and does not reveal `Eleni Nikolaou`.
- `SynchronizedCliPtyManualAuditValidatorTest`: paraphrase-only private-doc
  denial transcript fails; deterministic withheld phrase passes.

## Acceptance Criteria

- Focused runtime and e2e tests pass.
- `./gradlew.bat check --no-daemon` passes.
- `git diff --check` has no whitespace errors.
- T306 remains open until a real human PTY/JLine result JSON validates against
  the rebuilt installed binary.

## Completion Evidence

- Added a sanitized `userVisiblePrivacyNotice` to the model-context handoff
  decision.
- The tool loop now carries runtime-owned privacy notices to final-answer
  finalization.
- Final-answer finalization prepends deduplicated sanitized notices when content
  was withheld, so the completed transcript no longer depends on model
  paraphrase.
- The PTY validator remains strict and still rejects paraphrase-only private
  document denial transcripts.

Verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --tests "dev.talos.runtime.ToolLoopFinalAnswerFinalizerTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --tests "dev.talos.harness.SynchronizedCliPtyManualAuditValidatorTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

`git diff --check` reported CRLF normalization warnings only, no whitespace
errors.
