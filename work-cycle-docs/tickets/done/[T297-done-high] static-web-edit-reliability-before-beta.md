# T297 - Static Web Edit Reliability Before Beta

Status: done - static selector reliability closed by synchronized/live evidence and T308/T331; broader exact three-file static-site convergence remains T322
Severity: high
Release gate: yes for developer/code beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The live two-model audit showed both models failing a simple `script.js` selector fix. Talos prevented wrong-file edits and false success, but a local developer assistant must reliably execute this small repair.

## Evidence from current code

- Static repair paths and write-file nudges exist in `AssistantTurnExecutor`: `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:3743`, `:4022`, `:4033`, `:4236`.
- Static verifier has many `script.js` and `scripts.js` tests, but the live audit fixture still failed.
- The local source-backed audit report records GPT-OSS `old_string not found` and Qwen approval/repair drift for prompt 22.

## Evidence from tests/audits

- Live GPT-OSS prompt 22 failed after `talos.edit_file`.
- Live Qwen prompt 22 failed after a wrong edit attempt and approval drift.
- `scripts.js` was not edited, so target discrimination worked.
- Deterministic synchronized approval coverage now includes `static-web-selector-script-only-verified`: the scripted model reads `script.js`, performs one approved `talos.edit_file` replacement from `.missing-button` to `.cta-button`, leaves sibling `scripts.js` unchanged, records a checkpoint, and static web verification reports `PASSED`.
- Focused red/green evidence: `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` failed before the synchronized bank included `static-web-selector-script-only-verified`, then passed after adding the scenario.
- Scripted audit evidence: `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed with the 23-case bank; `build/synchronized-approval-audit/artifacts/static-web-selector-script-only-verified/audit-transcript.json` records `verificationStatus=PASSED` and `verificationSummary="Static web coherence checks passed for 1 mutated target(s)."`.
- Two-model live synchronized approval evidence on 2026-05-19 passed for the static-web scenario:
  - GPT-OSS: `local/manual-testing/synchronized-approval-live-gptoss-20260519-15case/static-web-selector-script-only-verified/audit-transcript.json`
  - Qwen: `local/manual-testing/synchronized-approval-live-qwen-20260519-15case/static-web-selector-script-only-verified/audit-transcript.json`
  - Both record one approved `talos.edit_file`, `checkpointStatus=CREATED`, `verificationStatus=PASSED`, and `verificationSummary="Static web coherence checks passed for 1 mutated target(s)."`.
  - Both workspace diffs touch only `script.js`; sibling `scripts.js` remains unchanged.
- The expanded 19-case synchronized live bank also passed for both models:
  - GPT-OSS: `local/manual-testing/synchronized-approval-live-gptoss-20260519-19case-r3/static-web-selector-script-only-verified/audit-transcript.json`
  - Qwen: `local/manual-testing/synchronized-approval-live-qwen-20260519-19case-r6/static-web-selector-script-only-verified/audit-transcript.json`
  - Both root summaries record `Scenarios: 19` and `Artifact scan: PASS`.
- The expanded 22-case GPT-OSS live rerun on 2026-05-19 reopened this ticket:
  - `local/manual-testing/synchronized-approval-live-gptoss-20260519-22case-r3/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md`
  - `local/manual-testing/synchronized-approval-live-gptoss-20260519-22case-r3/static-web-selector-script-only-verified/traces/last-trace.txt`
  - GPT-OSS over-inspected with read/list/grep calls, hit the generic tool-call limit, then the mutation retry emitted `talos.write_file` for `script_fixed.js`.
  - Runtime blocked `script_fixed.js` before approval because the expected target set was `script.js`; no approval was consumed and the workspace diff recorded no file changes.
  - This is a safe failure, not an unapproved mutation, but it is still a developer-beta reliability blocker.

## User impact

Developers cannot trust Talos as a strong local coding assistant if a one-line static web fix fails in live tool flow.

## Product risk

High for developer beta. Document support should not be built on top of a weak edit/repair loop if beta also claims code assistance.

## Runtime boundary affected

Tool-call repair loop, edit/write fallback, static verifier, approval sequencing, prompt-debug repair frames, and final-answer truthfulness.

## Non-goals

- No broad static web refactor.
- No visual/browser verification in this ticket unless current static verifier requires it.

## Required behavior

- If `talos.edit_file` fails with `old_string not found` after a read, Talos should recover with a bounded `talos.write_file` full-file replacement when the file is small and the target is unambiguous.
- BOM/display-prefix artifacts must not confuse old-string repair.
- Approval prompts must not drift into repeated denied operations when a deterministic repair is possible.
- Similar-file protection must remain: `script.js` and `scripts.js` are different.

## Proposed implementation

Write a failing e2e/scripted test using the exact live fixture. Debug whether the failure is caused by BOM handling, line-prefix handling, repair-loop tool selection, approval sequencing, or model prompt shape. Fix the smallest runtime path that makes the deterministic scenario pass.

## Tests

- `static_web_fixture_replaces_missing_button_with_submit_in_script_js`
- `static_web_fixture_does_not_edit_scripts_js`
- `old_string_miss_after_read_recovers_with_write_file_for_small_js`
- `bom_prefixed_readback_does_not_break_static_repair`
- `static_repair_false_success_blocked_when_no_mutation`
- `static_web_selector_script_only_verified` - added to the synchronized approval audit bank as `static-web-selector-script-only-verified`

## Acceptance criteria

- The exact audit fixture passes deterministically. Initial synchronized audit coverage added.
- Both live models pass the synchronized static-web selector probe. Full prompt-bank prompt 22 remains to be rerun before closing this ticket completely.
- Wrong-file safety and false-success blocking remain.

## Rollback / migration notes

Keep current false-success blocking even if repair remains imperfect. Do not trade safety for apparent success.

## Open questions

- Should repair fallback be runtime-deterministic for simple selector substitutions instead of another model retry?
- Should a compact single-target mutation continuation run before the generic tool-loop cap when a mutation request has already gathered enough read-only evidence but has not produced a valid write/edit call? Tracked separately in T308.

## Related files

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
