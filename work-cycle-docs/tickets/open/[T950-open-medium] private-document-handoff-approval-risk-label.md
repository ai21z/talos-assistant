# [T950-open-medium] Private-document handoff approval must not render as write risk

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product T929 manual PTY observation plus code inspection
- Date: 2026-07-04
- Talos version / commit: 0.10.8 /
  6c77d4b83f4d653a18beb94db2b33c8a244885a9
- Branch: `v0.9.0-beta-dev`
- Model/backend: `llama_cpp/qwen2.5-coder-14b` and `llama_cpp/gpt-oss-20b`
- Workspace fixture:
  `local/manual-workspaces/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/*/manual-pty`
- Raw transcript path:
  `local/manual-testing/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/artifacts/*/manual-pty/TRANSCRIPT.md`
  captured only the tail of the JLine session, so the risk-label observation is
  from the live terminal output in the current review turn, not validator-grade
  transcript evidence.
- Durable trace evidence:
  `local/manual-testing/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/artifacts/gptoss/manual-pty/isolated-home/.talos/sessions/traces/04b09b035797023c5ddc0a7d6ed5b9f802f5d53d-20260704120937/000003-trc-e0678537-8ca3-403a-8d44-2fd3afb41217.json`
- Approval choices: denied once, approved once
- Verification status: privacy behavior passed; approval UI risk label is wrong

Redacted prompt sequence:

```text
/privacy private on
Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.
n
/last trace
Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.
y
/last trace
```

Expected behavior:

```text
Private-document model handoff approval should clearly describe a protected
read/model-context handoff. It must not label the risk as "write", because no
workspace mutation is being approved.
```

Observed behavior:

```text
The approval window correctly says:

  Action  private document model handoff: talos.read_file
  permission: Private mode requires approval before sending extracted document
  text to model context.

but renders:

  Risk    write

The answer and trace behavior stayed privacy-correct. The problem is the
user-facing risk label.
```

## Classification

Primary taxonomy bucket: `PERMISSION`

Secondary buckets:

- `AUDITABILITY`
- `OUTCOME_TRUTH`
- `TRACE_REDACTION`

Blocker level: release QA blocker until fixed or explicitly waived

Why this level:

```text
Approval prompts are the user-control boundary. A private document handoff is a
read/privacy decision, not a write decision. Calling it "write" is not a leak or
mutation bug, but it weakens trust in the permission surface immediately before
public artifacts.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
`CliApprovalGate.inferRisk` is too lexical. It scans the approval detail, sees
`target:`, and classifies the prompt as write even when the approval
description is a once-only private-document model handoff.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/approval/CliApprovalGate.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolResultModelContextHandoff.java`
- `src/test/java/dev/talos/cli/approval/CliApprovalGateTest.java`
- `src/e2eTest/java/dev/talos/harness/ApprovalPromptContractTest.java`

Why a one-off patch is insufficient:

```text
The approval risk label is inferred for all approval prompts. Fix the risk
classification rule or introduce a typed risk override for the private-document
handoff; do not patch one rendered string in isolation.
```

## Goal

```text
Private-document model handoff approval windows render a non-mutating risk label
such as "sensitive read" or "private document handoff", while mutating approval
prompts still render "write" or "destructive" as appropriate.
```

## Non-Goals

- No weakening private-mode document handoff approval.
- No changing prompt text that the PTY validator byte-freezes unless the
  evidence contract is updated deliberately.
- No accepting session-remember approval for private-document handoff.
- No committing raw private transcripts.

## Implementation Notes

```text
Start with a failing `CliApprovalGateTest` or approval-rendering contract test:
`approveOnce("private document model handoff: talos.read_file", detail with
target)` must not render `Risk    write`.

Likely minimal fix: make `inferRisk` recognize "private document model handoff"
and/or "sending extracted document text to model context" before the generic
`target:` -> write fallback.
```

## Architecture Metadata

Capability:

- private-mode document extraction approval

Operation(s):

- read
- protected/private document model-context handoff

Owning package/class:

- `dev.talos.cli.approval.CliApprovalGate`
- `dev.talos.runtime.toolcall.ToolResultModelContextHandoff`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: once-only approval remains required
- Protected path behavior: raw private-document facts remain redacted from
  durable artifacts

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: approval UI and `/last trace` must show handoff approval
  without raw content
- Verification profile: focused approval UI test, then affected manual PTY lane
- Repair profile: no model reprompt change

Outcome and trace:

- Outcome/truth warnings: approval prompt must not imply mutation approval
- Trace/debug fields: existing `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_*`
  events remain unchanged

Refactor scope:

- Allowed: focused risk inference helper or typed risk override
- Forbidden: broad approval framework redesign

## Acceptance Criteria

- Private-document model handoff approval renders a non-mutating risk label.
- Write/edit/delete approvals still render the existing write/destructive
  labels.
- `approveOnce` still denies `a` / session-remember input.
- `/last trace` still records required/granted/denied handoff approvals.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `CliApprovalGateTest` or `ApprovalPromptContractTest` pins the
  private-document handoff risk label.
- Integration/executor test: not required.
- JSON e2e scenario: not required.
- Trace assertion: existing private-document handoff trace tests must remain
  green.

Manual rerun:

- Prompt family: T929 manual PTY private-document denial/approval lane.
- Workspace fixture: generated `manual-pty` fixture.
- Expected trace: `Approvals: required=1 granted=1 denied=0` on approved turn.
- Expected outcome: no patient name printed; no raw private-document fact in
  artifacts.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.approval.CliApprovalGateTest" --tests "dev.talos.harness.ApprovalPromptContractTest" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<fresh-audit-id>" --no-daemon
```

## Known Risks

- Risk-label wording can drift from the validator and manual runbook if changed
  without updating the evidence chain.

## Known Follow-Ups

- None.
