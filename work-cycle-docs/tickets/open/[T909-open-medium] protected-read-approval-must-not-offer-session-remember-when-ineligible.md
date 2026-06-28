# [T909-open-medium] Protected-read approval must not offer session remember when ineligible

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product Ask-mode manual audit
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 873b9ed2
- Installed build: 2026-06-28T20:44:48.560965600Z
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\talos-ask-mode-deep-20260628-2315\ask-workspace`
- Prompt-debug artifact: `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260628-235001.md`
- Trace path or `/last trace` summary: `trc-b99ccec3-ed31-4988-b37a-78e058e5b4c0`, then `trc-ca91ac84-91d1-4a63-bafa-77fe502b405b`
- File diff summary: none
- Approval choices: `a` on protected `.env` read, then `n` when the next `.env` read prompted again
- Checkpoint id: n/a
- Verification status: live installed audit reproduced; deterministic regression not yet added

Redacted prompt sequence:

```text
/mode ask
Read .env and report only the variable names, not the values. I will approve this protected read for the session.
a
/last trace

Read .env again and report only how many non-empty lines it contains. Do not print names or values.
```

Expected behavior:

```text
If protected reads are intentionally not session-rememberable, the approval UI
must not offer `a=yes for session`. If the UI offers and accepts `a`, the next
equivalent protected read in the same session should not prompt again.
```

Observed behavior:

```text
The approval window for `protected read: talos.read_file` displayed
`y = approve once . a = approve for session . Enter = deny`.

After selecting `a`, the next `.env` read still prompted for approval. Denying
that prompt produced `BLOCKED_BY_APPROVAL`.
```

Code evidence:

- `CliApprovalGate.approveFull(...)` accepts `a/all/always` and renders the
  session prompt for all full approvals:
  `src/main/java/dev/talos/cli/approval/CliApprovalGate.java`.
- `ApprovalPromptText.SESSION_PROMPT` advertises `a=yes for session`:
  `src/main/java/dev/talos/cli/ui/ApprovalPromptText.java`.
- `DeclarativePermissionPolicy` sets `rememberEligible=false` for protected
  read approvals:
  `src/main/java/dev/talos/runtime/policy/DeclarativePermissionPolicy.java`.
- `TurnProcessor` records session approval only when the response is
  `APPROVED_REMEMBER` and `permissionDecision.rememberEligible()` is true:
  `src/main/java/dev/talos/runtime/TurnProcessor.java`.
- `SessionApprovalPolicy` only remembers in-workspace writes and deliberately
  excludes sensitive paths such as `.env`:
  `src/main/java/dev/talos/runtime/SessionApprovalPolicy.java`.

## Classification

Primary taxonomy bucket:

- `PERMISSION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TRACE_REDACTION`

Blocker level:

- candidate follow-up

Why this level:

```text
The safe side held: protected reads did not silently become session-approved.
The bug is that the UI presents an approval option that policy ignores, so the
user receives a false control promise.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make `a` work for protected reads.
```

Architectural hypothesis:

```text
The approval prompt variant must be selected from the permission decision, not
from the generic approval gate. Ineligible operations should render the once-only
prompt or an explicit "session remember unavailable" message.
```

Likely code/document areas:

- `TurnProcessor`
- `CliApprovalGate`
- `ApprovalPromptText`
- `DeclarativePermissionPolicy`
- `SessionApprovalPolicyTest`

Why a one-off patch is insufficient:

```text
The same full approval gate is used for writes, protected reads, and commands.
The prompt must reflect `rememberEligible`, otherwise future ineligible approval
classes can repeat the same UX lie.
```

## Goal

```text
Protected-read approval prompts must not advertise session remember unless the
permission decision can actually remember the approval.
```

## Non-Goals

- No broad relaxation of protected-read approval.
- No session-wide auto-approval for `.env` or protected paths.
- No persistence of protected-read approvals across Talos processes.
- No weakening of protected read redaction.

## Implementation Notes

```text
Prefer plumbing `permissionDecision.rememberEligible()` into the approval prompt
selection. For ineligible approvals, call a once-only prompt or render a full
window whose choices omit `a`. Keep the current safer policy that protected
reads do not become session-remembered unless a separate explicit feature is
designed.
```

## Architecture Metadata

Capability:

- approval UI and permission policy

Operation(s):

- protected read

Owning package/class:

- `TurnProcessor`, `CliApprovalGate`, `DeclarativePermissionPolicy`, `SessionApprovalPolicy`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: protected reads remain approval-gated
- Protected path behavior: unchanged; no remembered auto-approval for `.env`

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: n/a
- Evidence obligation: live protected-read prompt plus focused approval tests
- Verification profile: no model call required for deterministic regression
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: prompt choices must match remembered behavior
- Trace/debug fields: permission decision should expose remember eligibility consistently

Refactor scope:

- `<allowed: approval prompt selection by remember eligibility, focused tests>`
- `<forbidden: remembered protected reads, protected-path policy weakening, broad permission rewrite>`

## Acceptance Criteria

- Protected-read approvals render a once-only prompt or otherwise omit
  `a=yes for session`.
- `a/all/always` is not accepted as a remembered approval for protected reads
  unless a future ticket explicitly designs that capability.
- In-workspace write approvals still offer and honor `a=yes for session`.
- Live Ask-mode protected-read prompt no longer promises a session approval that
  policy ignores.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `CliApprovalGateTest` or approval-renderer test for once-only protected read prompt
- Integration/executor test: `TurnProcessorPermissionPolicyTest` for protected read remember ineligible
- JSON e2e scenario: n/a
- Trace assertion: permission decision records rememberEligible=false for protected reads

Manual/TalosBench rerun:

- Prompt family: Ask mode `.env` protected-read approval
- Workspace fixture: fresh fixture with `.env`
- Expected trace: approval required/granted once, no session remember claim
- Expected outcome: no misleading `a=yes for session` prompt

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.approval.CliApprovalGateTest" --tests "dev.talos.runtime.TurnProcessorPermissionPolicyTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Known Risks

- If only the rendered text changes but `a` is still accepted silently, the UX
  remains ambiguous. Tests should cover both prompt text and response handling.

## Known Follow-Ups

- None.

