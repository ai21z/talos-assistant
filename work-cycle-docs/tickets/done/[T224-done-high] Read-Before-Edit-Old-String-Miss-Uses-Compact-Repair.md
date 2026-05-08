# T224 - Read-Before-Edit Old-String Miss Uses Compact Repair

Status: done
Severity: high
Created: 2026-05-08
Completed: 2026-05-08

## Problem

The T223 focused audit fixed old-string compact repair target casing, but GPT-OSS exposed a
separate read-before-edit failure shape:

- The model reads `README.md`.
- The model calls `talos.edit_file` for `README.md`.
- The edit fails with `old_string not found`.
- Talos falls back to generic expected-target progress and later stops with
  `[Action obligation failed: retry could not fit in the context budget.]`.

This is not a model wording problem. The runtime has already seen the target file content, but
`ToolCallExecutionStage` clears `state.successfulReadCalls` on a mutating tool failure. That
discarded readback prevents `ToolCallRepromptStage.nextOldStringMissCompactRepair` from building
the compact target-only repair frame.

## Evidence

Focused audit:

`local/manual-testing/t223-oldstr-case-audit-20260508-065820`

Relevant transcript locations:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around lines 2295-2299:
  read `README.md`, then failed `edit_file -> README.md` twice with `old_string not found`.
- Same transcript around line 2274:
  `[Action obligation failed: retry could not fit in the context budget.]`.
- Same transcript around lines 2615-2676:
  generic `[Expected target progress]` was injected instead of `[OldStringMissRepair]`.

Relevant code:

- `ToolCallExecutionStage` stores readback content in `state.successfulReadCalls`.
- `ToolCallExecutionStage` clears that readback on mutating failures.
- `ToolCallRepromptStage.nextOldStringMissCompactRepair` requires readback content before it can
  build `[OldStringMissRepair]`.

## Scope

- Preserve successful readback evidence for an `edit_file` `old_string not found` failure on the
  same expected target when no successful mutation has occurred after that read.
- Ensure read-before-edit old-string misses use the compact target-only old-string repair frame.
- Keep the existing safety behavior for successful mutations: successful write/edit operations must
  still invalidate stale readbacks.
- Keep compact repair bounded to one attempt per target.
- Do not broaden this into general memory/context retention.

## Acceptance

- Regression test proves read-before-edit then `old_string not found` triggers `[OldStringMissRepair]`
  instead of generic context-budget failure.
- Regression test proves successful mutation still clears stale readback evidence.
- Existing T222/T223 compact repair tests still pass.
- Full unit suite and build/install pass.
- Focused old-string repair audit is rerun with Qwen and GPT-OSS before any larger audit.

## Implementation

- `ToolCallExecutionStage` now preserves successful readback evidence when a non-stale
  `talos.edit_file` call fails with `old_string not found`.
- Successful mutations still clear readback evidence, so compact repair does not use content read
  before a later successful write/edit.
- The old-string compact repair path can now handle the GPT-OSS shape where the model reads
  `README.md` before a failed edit.

## Verification

- Red/green regression tests:
  `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.readBeforeEditOldStringMissUsesCompactRepairBeforeContextBudgetFailure --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairDoesNotUseReadbackFromBeforeSuccessfulMutation --no-daemon`
- Adjacent old-string compact repair cluster passed.
- Full unit suite passed:
  `.\gradlew.bat test --no-daemon`
- Full build/install passed:
  `.\gradlew.bat build installDist --no-daemon`

## Focused Audit

Audit directory:

`local/manual-testing/t224-read-before-edit-oldstr-audit-20260508-071605`

Result:

- T224 pass. GPT-OSS now shows `talos.read_file -> README.md [ok]`,
  `talos.edit_file -> README.md [failed]`, `[OldStringMissRepair] Target: README.md`, and
  `talos.write_file -> README.md [ok]`.
- Qwen also uses `[OldStringMissRepair] Target: README.md` in the old-string miss path.
- The previous GPT-OSS context-budget failure for read-before-edit old-string miss was not
  reproduced.
- The audit exposed a separate Qwen read-only exact-content context-budget issue; follow-up ticket
  T225 tracks that separately.
