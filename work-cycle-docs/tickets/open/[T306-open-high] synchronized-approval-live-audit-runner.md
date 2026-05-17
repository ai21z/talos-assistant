# T306 - Synchronized Approval Live Audit Runner

Status: open
Severity: high / P0 for private-document beta
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-18
Owner: unassigned

## Problem

The current live-audit script intentionally avoids approval-sensitive prompts because piped stdin can desynchronize approval responses and later slash commands. That protects audit integrity, but it leaves approval grant/deny behavior as a manual transcript requirement.

## Evidence from current code

- `RunCmd` and `TalosBootstrap` route scripted stdin and approval prompts through a shared input owner.
- `scripts/run-capability-live-audit.ps1` now generates `PRIVATE-FOLDER-MANUAL-AUDIT-RUNBOOK.md` for approval-sensitive probes instead of pretending they are automated.
- Private-folder bank audit `capability-live-audit-20260518-004603` passed non-interactive private-folder probes, but did not automate approval grant/deny prompts.

## Evidence from tests/audits

- Scripted private-folder bank: `capability-live-audit-20260518-004603`.
- The generated manual runbook lists protected-read denial, approved local-display read, explicit send-to-model opt-in, trace, prompt-debug, provider-body, session, turn JSONL, log, and artifact-scan capture requirements.

## User impact

Without synchronized approval capture, maintainers cannot fully reproduce the private-document release gate from one command. They must manually run approval-sensitive prompts and collect evidence carefully.

## Product risk

High. Approval behavior is a core Talos trust boundary. Private-document beta should not rely on unstructured human notes for approval grant/deny evidence.

## Runtime boundary affected

Approval prompts, protected direct reads, extracted-document send-to-model opt-in, prompt-debug, provider bodies, traces, sessions, turn JSONL, logs, and artifact scans.

## Non-goals

- No arbitrary shell automation.
- No bypassing approval policy.
- No fake "approved" state in live audit results.

## Required behavior

- A synchronized runner must be able to send user prompts and approval responses without stdin drift.
- It must capture approval prompt text, response, final answer, `/last trace`, prompt-debug save, provider body, session/turn artifacts, logs, workspace diff, and artifact scan result.
- It must distinguish approval denied, approval granted local-display-only, and explicit send-to-model opt-in cases.
- It must fail closed if the expected approval prompt does not appear.

## Proposed implementation

Add either:

1. a pseudo-terminal based PowerShell/Java harness that can wait for approval prompts and respond deliberately, or
2. a Talos test-mode live-audit protocol that exposes deterministic approval responses without weakening production approval behavior.

Keep the existing `-PrivateFolderBank` scripted path for non-interactive probes. Use the synchronized runner only for approval-sensitive cases.

## Tests

- approval_runner_denies_protected_read_and_captures_trace
- approval_runner_grants_local_display_read_without_model_handoff
- approval_runner_fails_if_approval_prompt_missing
- approval_runner_explicit_send_to_model_records_scope
- approval_runner_artifact_scan_fails_on_raw_private_fact

## Acceptance criteria

- Approval-sensitive private-folder prompts can run from a reproducible command.
- The resulting artifact directory includes all required evidence files.
- Targeted artifact scan passes.
- No private-document release claim is made until this runner or an equivalent human-operated transcript package exists and passes.

## Remaining blockers

- Choose pseudo-terminal runner versus test-mode protocol.
- Define exact transcript schema.
- Decide whether explicit extracted-document send-to-model should be per-turn approval, config-only, or both.

## Open questions

- Should this runner live as PowerShell only, Java e2e harness, or both?
- Should approval-sensitive live audits use the same model/backend preflight as `run-capability-live-audit.ps1`?

## Related files

- `scripts/run-capability-live-audit.ps1`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/cli/repl/slash/PrivacyCommand.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
