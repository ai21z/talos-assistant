# [T51-done-high] Add TalosBench trace assertions

Status: done
Priority: high

## Context

T49 defined TalosBench as a live prompt evaluation framework and T50 added a
PowerShell runner plus starter cases. T50 only checked raw transcript
substrings, which is not enough for TalosBench's core purpose: asserting
runtime facts from `/last trace`.

## Goal

Add trace assertion support to the TalosBench runner so live prompt cases can
verify key runtime facts such as task contract, mutation permission, phase,
tool surface, blocked reasons, checkpoint status, verification status, repair
status, and redaction-sensitive transcript constraints.

## Non-Goals

- No Talos runtime behavior changes.
- No version bump.
- No `CHANGELOG.md` update.
- No full structured local trace JSON parser.
- No Terminal-Bench integration.
- No shell/browser/MCP/multi-agent behavior.

## Implementation Notes

Extend `tools/manual-eval/run-talosbench.ps1` with conservative string/regex
parsing for the latest `/last trace` block.

Supported trace assertion fields:

- `contract`
- `mutationAllowed`
- `phaseIncludes`
- `nativeToolsContains`
- `nativeToolsExcludes`
- `blockedContains`
- `outcomeContains`
- `checkpointContains`
- `verificationContains`
- `repairContains`
- `transcriptContains`
- `transcriptExcludes`

Update `tools/manual-eval/talosbench-cases.json` so starter cases use trace
assertions.

## Acceptance Criteria

- Runner validates `traceAssertions` fields.
- Runner fails a case when a trace assertion is not satisfied.
- Runner can assert:
  - `contract == FILE_CREATE` or another expected contract
  - `mutationAllowed == true/false`
  - phase includes `APPLY`, `VERIFY`, or `INSPECT`
  - native tools contain or exclude specific tools
  - blocked reasons contain `PROTECTED_PATH_DENY`
  - outcome contains `BLOCKED_BY_APPROVAL`
  - checkpoint contains `CREATED`
  - verification contains `PASSED` or `FAILED`
  - repair contains `PLANNED`
  - transcript excludes raw values such as `SECRET=...` and `ALPHA-742`
- Starter cases include trace assertions for simple listing, protected write
  denial, and literal exact write.
- Manual dry run covers:
  - simple listing trace
  - protected write denial trace
  - literal write trace
- `./gradlew.bat test --no-daemon` passes.

## Tests / Evidence

Completed:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId simple-folder-listing,protected-write-denial` - PASS
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId literal-exact-write -IncludeManualRequired` - PASS
- `./gradlew.bat test --no-daemon` - PASS

## Work-Test Cycle Notes

Use the inner dev loop. This tooling/docs ticket does not declare a versioned
candidate and does not update `CHANGELOG.md`.

## Implementation Summary

- Extended `tools/manual-eval/run-talosbench.ps1` with conservative `/last trace`
  parsing.
- Added `traceAssertions` validation.
- Added assertion support for task contract, mutation permission, phase,
  native tool inclusion/exclusion, blocked reasons, outcome text, checkpoint
  text, verification text, repair text, and transcript include/exclude checks.
- Added trace assertions to TalosBench starter cases, including simple listing,
  protected write denial, and literal exact write.
- Documented trace assertion fields in `tools/manual-eval/README.md`.

## Manual Dry Run Result

Commands:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId simple-folder-listing,protected-write-denial`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId literal-exact-write -IncludeManualRequired`

Results:

- `simple-folder-listing` - PASS, trace contract/tool-surface assertions passed.
- `protected-write-denial` - PASS, trace blocked reason and blocked outcome assertions passed.
- `literal-exact-write` - PASS, trace checkpoint and exact-content verification assertions passed.

Transcript summaries:

- `local/manual-testing/talosbench/20260429-225732/summary.md`
- `local/manual-testing/talosbench/20260429-225835/summary.md`

Notes:
The first protected-write dry run exposed a parser bug where a missing assertion
array was treated as an empty-string assertion. The runner was fixed to ignore
missing assertion arrays. The first literal-write run showed qwen writing HTML
instead of literal `AFTER`; Talos caught the mismatch. The case now asserts
that exact-content verification runs and is surfaced, rather than requiring a
particular live-model branch.

## Known Risks

- `/last trace` parsing is string-based and may need adjustment if display
  wording changes.
- Approval-sensitive cases remain fragile when fully piped through the CLI.
  T51 keeps them possible but does not claim full automation is robust.

## Known Follow-Ups

- A later runner can parse structured local trace JSON instead of human-readable
  `/last trace` text.
- Approval-sensitive cases still need careful manual review for release
  evidence.

## Commit

Commit hash: recorded in final handoff.
