# T265 - TalosBench Final-Turn Output Assertion Scope

Severity: medium
Status: done

## Problem

The post-T262-T264 broader TalosBench audit produced a GPT-OSS blocker for
`t59-no-workspace-suppresses-active-context`, but the transcript showed the
runtime behavior under test was correct:

- final turn contract: `SMALL_TALK`
- final turn native tools: `none`
- final turn prompt tools: `none`
- final turn tool calls: `0`
- prompt audit active task context: `SUPPRESSED`

The blocker was caused by `forbiddenOutputSubstrings` being applied to the
whole two-turn transcript. GPT-OSS mentioned `talos.write_file` in the first
read-only proposal answer, while the T59 assertion was intended to guard the
second no-workspace follow-up.

## Implementation

- Added `Get-LastNaturalTurnBlock` to the TalosBench runner.
- Added optional `requiredFinalTurnSubstrings` and
  `forbiddenFinalTurnSubstrings` case fields.
- Kept `requiredOutputSubstrings` and `forbiddenOutputSubstrings` as
  transcript-wide checks for whole-run invariants.
- Moved the T59 no-workspace forbidden tool-name assertion to final-turn scope.
- Documented the new schema fields in `tools/manual-eval/README.md`.

## Verification

Red check:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
  - Failed before implementation because `Get-LastNaturalTurnBlock` did not
    exist.

Harness checks:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`

Focused Qwen/GPT-OSS rerun:

- `local/manual-testing/t265-final-turn-scope-audit-20260513-164525/`
- `t59-no-workspace-suppresses-active-context` passed for both models.
- GPT-OSS final turn trace showed `SMALL_TALK`, no visible tools, tool calls
  `0`, and `activeTaskContext{state=SUPPRESSED}`.

Broader Qwen/GPT-OSS rerun:

- `local/manual-testing/post-t265-broader-talosbench-audit-20260513-164627/`
- Both model runs exited `0`.
- All non-manual runnable cases passed; approval-sensitive cases remained
  `MANUAL_REQUIRED`.
