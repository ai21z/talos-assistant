# T223 - Preserve Expected Target Casing In Old-String Compact Repair

Status: done
Severity: high
Created: 2026-05-08
Completed: 2026-05-08

## Problem

The T222 focused audit showed that old-string compact repair correctly detects an `edit_file`
old_string miss and successful readback, but the repair target is lowercased before being sent
back to the model. For `README.md`, the compact repair prompt says `readme.md`, and both Qwen
and GPT-OSS write `readme.md` instead of preserving the requested `README.md` target.

This is a runtime target-normalization bug, not a prompt-construction absence. Lowercase keys are
valid for comparison, but they must not become user/model-facing mutation targets.

## Evidence

Focused audit:

`local/manual-testing/t222-oldstr-audit-20260508-064511`

Observed in both model transcripts:

- first proposal apply hits `talos.edit_file -> README.md [failed]` with `old_string not found`
- runtime readback succeeds for `README.md`
- compact prompt carries debug tags `pending-action-obligation, old-string-miss-compact-repair`
- compact prompt says `[OldStringMissRepair] Target: readme.md`
- compact repair then writes `readme.md`
- changed-files summary records `readme.md`, while the requested target was `README.md`

## Scope

- Preserve original expected target display path/casing when computing remaining expected mutation targets.
- Keep lowercase/canonical keys only for internal matching.
- Old-string compact repair prompt must name the exact expected target casing, e.g. `README.md`.
- Old-string compact repair obligation must reject case-mismatched model writes such as `readme.md` when the pending target is `README.md`.
- Do not broaden this into a full path-canonicalization refactor.

## Acceptance

- Regression test proves compact repair prompt contains `Target: README.md`, not `Target: readme.md`.
- Regression test proves compact repair rejects `talos.write_file(path=readme.md)` before execution when pending target is `README.md`.
- Existing T222 old-string compact repair tests still pass.
- Focused audit is rerun after the fix.

## Implementation

- `remainingExpectedMutationTargets` now returns display paths with original target casing while
  retaining lowercase/canonical keys only for internal matching.
- Old-string compact repair stores prompted targets by normalized key but displays the original
  expected target path to the model.
- Pending old-string repair target enforcement now compares the pending target path
  case-sensitively, so `talos.write_file(path=readme.md)` does not satisfy a pending
  `README.md` obligation.

## Verification

- Focused T223 regression tests:
  `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairPreservesExpectedTargetCasing --tests dev.talos.runtime.ToolCallLoopTest.oldStringMissCompactRepairRejectsCaseMismatchedTargetBeforeExecution --no-daemon`
- Adjacent old-string/static repair cluster passed.
- Full unit suite passed:
  `.\gradlew.bat test --no-daemon`
- Full build/install passed:
  `.\gradlew.bat build installDist --no-daemon`

## Focused Audit

Audit directory:

`local/manual-testing/t223-oldstr-case-audit-20260508-065820`

Result:

- T223 casing defect fixed. Qwen prompt-debug shows `[OldStringMissRepair] Target: README.md`,
  followed by `talos.write_file -> README.md [ok]`.
- The previous lowercase target leak (`readme.md`) was not reproduced.
- GPT-OSS exposed a separate remaining old-string compact repair gap for read-before-edit failures;
  follow-up ticket T224 tracks that separately.
