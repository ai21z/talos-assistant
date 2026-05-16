# T297 - Static Web Edit Reliability Before Beta

Status: open
Severity: high
Release gate: yes for developer/code beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
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

## Acceptance criteria

- The exact audit fixture passes deterministically.
- Both live models pass prompt 22 in the next prompt-bank audit.
- Wrong-file safety and false-success blocking remain.

## Rollback / migration notes

Keep current false-success blocking even if repair remains imperfect. Do not trade safety for apparent success.

## Open questions

- Should repair fallback be runtime-deterministic for simple selector substitutions instead of another model retry?

## Related files

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
