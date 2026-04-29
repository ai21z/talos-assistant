# [T50-done-high] Implement TalosBench live prompt runner

Status: done
Priority: high

## Context

T49 designed TalosBench as a live/manual evaluation matrix for installed Talos
and real local models. The next step is a repeatable runner that can create
controlled workspaces, feed prompt sequences to installed Talos, collect raw
local transcripts, and produce a concise summary without hiding failures.

## Goal

Create a local TalosBench runner for installed Talos prompt sweeps.

The runner should make manual/live evaluation repeatable while keeping raw
transcripts local and untracked.

## Non-Goals

- No Talos runtime behavior changes.
- No version bump.
- No `CHANGELOG.md` update.
- No Terminal-Bench integration.
- No shell/browser/MCP/multi-agent capabilities.
- No committed raw transcripts from `local/manual-testing/`.

## Implementation Notes

Create:

- `tools/manual-eval/run-talosbench.ps1`
- `tools/manual-eval/talosbench-cases.json`
- `tools/manual-eval/README.md`
- a tracked safe summary/template under `docs/evaluation/`

The runner should:

- create controlled workspaces under `local/manual-workspaces/talosbench/<case-id>/`
- run installed Talos with scripted input
- save raw transcripts under `local/manual-testing/talosbench/<timestamp>/`
- produce a Markdown summary table with case id, status, category, blocker
  state, transcript path, and notes
- support case fields listed in the ticket request
- mark approval-sensitive cases as `MANUAL_REQUIRED` unless explicitly run
  with `-IncludeManualRequired`

## Acceptance Criteria

- Runner script exists at `tools/manual-eval/run-talosbench.ps1`.
- Starter cases exist at `tools/manual-eval/talosbench-cases.json`.
- README documents prerequisites, usage, output paths, and manual approval
  caveats.
- Runner supports:
  - `id`
  - `category`
  - `workspaceFixture`
  - `prompts`
  - `expectedContract`
  - `expectedToolsAllowed`
  - `forbiddenOutputSubstrings`
  - `requiredOutputSubstrings`
  - `blockerConditions`
  - `notes`
- Runner includes starter cases for:
  - capability prompt family
  - privacy no-workspace
  - mutation create BMI
  - simple folder listing
  - protected write denial
  - protected read denial
  - literal exact write
  - checkpoint restore
  - failed static verification truthfulness
  - trace redaction
- Raw transcripts are written only under ignored local manual-testing paths.
- At least one non-approval dry run is performed for:
  - capability prompt
  - simple folder listing
  - privacy no-workspace
- `./gradlew.bat test --no-daemon` passes.

## Tests / Evidence

Completed:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - PASS, validated 10 cases.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ListCases` - PASS.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId capability-onboarding,privacy-no-workspace,simple-folder-listing` - PASS after correcting an over-specific expected substring.
- `./gradlew.bat test --no-daemon` - PASS.

Dry-run transcript summary:

- `local/manual-testing/talosbench/20260429-225019/summary.md`
- `capability-onboarding` - PASS
- `privacy-no-workspace` - PASS
- `simple-folder-listing` - PASS

## Work-Test Cycle Notes

Use the inner dev loop. This tooling/docs ticket does not declare a versioned
candidate and does not update `CHANGELOG.md`.

## Implementation Summary

- Added `tools/manual-eval/run-talosbench.ps1`.
- Added starter prompt cases in `tools/manual-eval/talosbench-cases.json`.
- Added runner documentation in `tools/manual-eval/README.md`.
- Added tracked summary template `docs/evaluation/talosbench-summary-template.md`.
- Runner creates controlled workspaces under
  `local/manual-workspaces/talosbench/<case-id>/`.
- Runner writes raw transcripts and a local run summary under
  `local/manual-testing/talosbench/<timestamp>/`.
- Runner supports selected case ids, listing, validation-only mode, manual case
  skipping, and optional `-IncludeManualRequired`.
- Runner exits non-zero for `FAIL` or `BLOCKER` cases so failures are not
  hidden.

## Known Risks

- Interactive approvals are fragile when fully piped through a CLI process.
  Approval-sensitive cases should be marked `MANUAL_REQUIRED` until a later
  runner can robustly drive approvals.
- Transcript assertions are string-based in T50. T51 should add structured
  trace assertion parsing.

## Manual Dry Run Result

Command:
`pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId capability-onboarding,privacy-no-workspace,simple-folder-listing`

Model:
Installed Talos default local model, observed as `qwen2.5-coder:14b` in
transcripts.

Cases:

- `capability-onboarding` - PASS
- `privacy-no-workspace` - PASS
- `simple-folder-listing` - PASS

Output:
`local/manual-testing/talosbench/20260429-225019/summary.md`

Notes:
The first dry run exposed an over-specific case assertion expecting the exact
phrase `approved file changes`. The installed capability answer used the
equivalent phrase `apply file changes only after approval`. The case was
updated to assert the invariant rather than the exact alternate wording.

## Known Follow-Ups

- T51 should add structured `/last trace` parsing and assertions.
- Approval-sensitive cases should remain `MANUAL_REQUIRED` until a more robust
  interactive runner exists.

## Commit

Commit hash: recorded in final handoff.
