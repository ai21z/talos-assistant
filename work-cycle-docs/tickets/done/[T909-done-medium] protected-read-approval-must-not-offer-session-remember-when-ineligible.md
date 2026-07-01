# [T909-done-medium] Protected-read approval must not offer session remember when ineligible

Status: done
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
- Verification status: deterministic regression added and focused gates green

Additional installed-product corroboration:

- Source: installed-product Plan-mode manual audit
- Date: 2026-06-28 / prompt-debug saved as `20260629-000800` local time
- Repo HEAD at audit: `1b79cb11`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\plan-mode-deep-20260628-235632\plan-workspace`
- Prompt-debug artifact copy: `local/manual-testing/plan-mode-deep-20260628-235632/artifacts/prompt-debug/prompt-debug-20260629-000800.md`
- Trace ids: `trc-1d694bbb-f990-4f95-9b78-a858ec62c94f`, then `trc-80c2180c-45a7-4502-8ac8-285c68b0dd63`
- Approval choices: `a` on protected `.env` read, then `n` when the next `.env` read prompted again

Additional Agent-mode corroboration:

- Source: installed-product Agent-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `6f583801`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\agent-mode-deep-20260629-075710\agent-workspace`
- Prompt-debug artifact copy: `local/manual-testing/agent-mode-deep-20260629-075710/artifacts/prompt-debug/prompt-debug-20260629-081157.md`
- Trace ids: `trc-7b553c55-6c70-473b-be2c-b8bfc272fe79`, `trc-d3ed5883-0985-48e3-9cad-c6f13255c9f1`
- Approval choices: `n` on first protected read; `y` on second protected read

Additional Auto-mode corroboration:

- Source: installed-product Auto-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `c91e3060`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\auto-mode-deep-20260629-091500\auto-workspace`
- Prompt-debug artifact copy: `local/manual-testing/auto-mode-deep-20260629-091500/artifacts/prompt-debug/prompt-debug-20260629-084647.md`
- Trace ids: `trc-b018b4a6-b21f-45c8-bb84-a29c42ff2fdf`, `trc-e1af13c4-9262-4cff-8a0b-786510e2da0f`
- Approval choices: `n` on first protected read; `y` on second protected read

Additional GPT-OSS Auto-mode corroboration:

- Source: installed-product GPT-OSS Auto-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `3efe1d60`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-auto-mode-deep-20260629-093500/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-auto-mode-deep-20260629-093500\auto-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-auto-mode-deep-20260629-093500/artifacts/prompt-debug/prompt-debug-20260629-100448.md`
- Trace ids: `trc-3d304ed4-f58a-42cf-8615-dee111273f5a`, `trc-b1091a3a-f805-4b37-a638-ad567e07b115`
- Approval choices: `n` on first protected read; `y` on second protected read

Additional GPT-OSS Ask-mode corroboration:

- Source: installed-product GPT-OSS Ask-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `29672962`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-ask-mode-deep-20260629-101500/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-ask-mode-deep-20260629-101500\ask-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-ask-mode-deep-20260629-101500/artifacts/prompt-debug/prompt-debug-20260629-102245.md`
- Trace ids: `trc-983319ac-d89d-4d2e-bdc6-8a39aa720d4b`, `trc-18c26649-4b44-4859-8cc2-d3901b8ef669`
- Approval choices: `n` on first protected read; `y` on second protected read

Additional GPT-OSS Plan-mode corroboration:

- Source: installed-product GPT-OSS Plan-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `f210987e`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-plan-mode-deep-20260629-103000/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-plan-mode-deep-20260629-103000\plan-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-plan-mode-deep-20260629-103000/artifacts/prompt-debug/prompt-debug-20260629-104045.md`
- Trace id: `trc-760a78aa-4cbd-48c9-b985-2fbcc1fcfcff`
- Approval choice: `n` on protected read

Additional GPT-OSS Agent-mode corroboration:

- Source: installed-product GPT-OSS Agent-mode manual audit
- Date: 2026-06-29
- Repo HEAD at audit: `ab4b3706`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-agent-mode-deep-20260629-104800/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-agent-mode-deep-20260629-104800\agent-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-agent-mode-deep-20260629-104800/artifacts/prompt-debug/prompt-debug-20260629-110433.md`
- Trace id: `trc-7cc71886-ccb4-4176-8830-a7466fc749fb`
- Approval choice: `n` on protected read

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

The same behavior reproduced in `/mode plan`: selecting `a` granted the current
protected read, but the next `.env` read prompted again and denial produced
`BLOCKED_BY_APPROVAL`. The safe boundary held; the UI promise remained false.

The same ineligible prompt surface is visible in `/mode agent`: protected-read
approval windows still advertise `y = approve once . a = approve for session .
Enter = deny` for `.env` reads. The Agent audit did not reselect `a`, because
Ask/Plan already proved the mismatch; it confirms the misleading choice is not
mode-local.

The same ineligible prompt surface is visible in `/mode auto`: protected-read
approval windows still advertise `y = approve once . a = approve for session .
Enter = deny` for `.env` reads. Deny contained the read with
`BLOCKED_BY_APPROVAL`; approve-once allowed a value-minimized answer. The safe
boundary held; the UI still advertised an ineligible session option.

The same ineligible prompt surface also reproduced cross-model in GPT-OSS
`/mode auto`. The protected-read deny path contained the read, and the
approve-once path returned only the requested variable name, but the approval
window still advertised `a = approve for session` for a protected read.

The same ineligible prompt surface also reproduced cross-model in GPT-OSS
`/mode ask`. The protected-read deny path contained the read with
`BLOCKED_BY_APPROVAL`, and the approve-once path returned only the requested
variable name, but the approval window still advertised
`a = approve for session` for a protected read.

The same ineligible prompt surface also reproduced cross-model in GPT-OSS
`/mode plan`. The protected-read deny path contained the read with
`BLOCKED_BY_APPROVAL`, and no protected content was shown, but the approval
window still advertised `a = approve for session` for a protected read.

The same ineligible prompt surface also reproduced cross-model in GPT-OSS
`/mode agent`, even while an unrelated write session approval was active. The
protected-read deny path still required its own approval and contained the read
with `BLOCKED_BY_APPROVAL`, but the approval window still advertised
`a = approve for session` for a protected read.
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
- `TurnProcessor` records `rememberEligible`, but the ASK path still invokes
  `approvalGate.approveFull(...)` unconditionally, so the rendered approval
  surface is not selected from the eligibility bit:
  `src/main/java/dev/talos/runtime/TurnProcessor.java`.
- `CliApprovalGate.approveOnce(...)` already exists and deliberately does not
  offer or accept session remember, proving the fix is a call-path/interface
  selection issue rather than a string-only copy edit:
  `src/main/java/dev/talos/cli/approval/CliApprovalGate.java`.
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
The same `TurnProcessor` tool-call ASK path currently routes writes, protected
reads, and command approvals through the full approval gate. The prompt must
reflect `rememberEligible`, otherwise future ineligible approval classes can
repeat the same UX lie.
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

This is a structural approval-surface fix: the implementation must select an
approval option surface from `PermissionDecision.rememberEligible()` or pass an
equivalent options object through the approval gate. Editing only
`ApprovalPromptText` would be insufficient if `a/all/always` remains accepted on
an ineligible path.
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

Deterministic regression:

- Unit test: `CliApprovalGateTest` covers once-only approval prompt rendering and response handling.
- Integration/executor test: `TurnProcessorPermissionPolicyTest.protectedReadUsesOnceOnlyApprovalSurface`
  proves protected reads use `approveOnce`, not `approveFull`.
- Existing integration test: `TurnProcessorPermissionPolicyTest.sessionRememberStillBypassesGateForSafeWriteButNotProtectedPath`
  proves safe writes still use and honor session remember.
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

Executed green:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.TurnProcessorPermissionPolicyTest.protectedReadUsesOnceOnlyApprovalSurface" --no-daemon
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
