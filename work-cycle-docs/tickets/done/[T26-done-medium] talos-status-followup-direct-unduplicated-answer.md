# [T26-done-medium] Ticket: Status Follow-Up Should Be Direct And Unduplicated
Date: 2026-04-28
Priority: medium
Status: done
Architecture references:
- work-cycle-docs/new-work.md
- docs/architecture/talos-harness-source-of-truth.md
- docs/architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T19-done-high] talos-status-followup-must-use-verified-outcome.md

## Why This Ticket Exists

T19 correctly makes status follow-ups preserve the previous verified outcome. Manual testing showed the behavior is safe but still awkward: answers can repeat the same status sentence multiple times and do not always start with a direct yes/no/partial status.

This is not as dangerous as mutation leakage, but it affects user trust and natural flow.

## Problem

Reproduced transcripts:

- `local/manual-testing/deep-review/bmi-empty-c-repair-transcript.txt`
- `local/manual-testing/deep-review/bmi-empty-c-writefile-repair-transcript.txt`

Observed status answer:

```text
The previous verified result says the last change is not complete.

The previous verified result says the last change is not complete.

The previous verified result says the last change is not complete.
```

The answer was truthful and read-only, but repeated. In other status checks, Talos preserved the outcome but did not lead with a user-friendly direct statement such as:

```text
No. Some files changed, but the BMI calculator is still not verified complete.
```

## Goal

Prior-change status follow-ups should answer directly and once, then include concise verified details.

## Scope

In scope:
- Deduplicate repeated verified-outcome preambles.
- Prefer a direct first sentence for status questions:
  - `Yes, static verification passed...`
  - `No, no file changed...`
  - `Partially. Some files changed, but verification failed...`
- Preserve T19 truthfulness and read-only behavior.

Out of scope:
- Running new broad verification.
- Mutating files on status questions.
- Changing the underlying static verifier.

## Proposed Work

- Adjust `verifiedFollowUpSummaryIfNeeded(...)` / `renderVerifiedFollowUpSummary(...)` to avoid nested repeated summaries from history.
- Consider extracting the latest verified outcome block instead of embedding prior summaries recursively.
- Add tests for repeated status follow-up after repeated status follow-up.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit tests:
  - first status follow-up preserves partial outcome,
  - second status follow-up does not duplicate the preamble,
  - answer does not claim completion unless prior outcome supports it.
- E2E JSON scenario for repeated `did you make the changes?`.
- Manual Talos check after a partial BMI task.

## Acceptance Criteria

- Status follow-up remains verify-only/read-only.
- Final answer starts with a direct verified status.
- Repeated follow-up does not duplicate the same sentence.
- No completion language appears for partial/failed outcomes.

## Evidence

Manual deep-review result on 2026-04-28:

- Repeated status follow-ups after partial BMI failure produced duplicated `The previous verified result says...` lines.

Additional non-technical phrasing evidence on 2026-04-28:

- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`
  - Prompt: `Is it working now?`
  - Talos correctly stayed `VERIFY_ONLY` and preserved the partial verified outcome.
  - The answer was truthful but not user-friendly for a non-technical user. It repeated the internal verified summary rather than starting with a simple answer such as:
    - `No. Some HTML changed, but the BMI calculator is still not verified complete.`

T26 should optimize for a regular user's status question, not just architecture correctness.

## Current Code Read

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/42-partial-followup-summary-uses-verified-history.json`
- `src/e2eTest/resources/scenarios/53-status-followup-preserves-partial-outcome.json`

## Planned Tests

- Add focused `AssistantTurnExecutorTest` coverage for repeated
  `did you make the changes?` follow-ups after a partial verified outcome.
- Add focused assertions that the answer starts with a direct status and does
  not repeat the status preamble.
- Add one deterministic JSON e2e scenario for repeated status follow-up.
- Run focused executor tests, focused e2e, full `e2eTest`, and `check`.

## Implementation Summary

- Reworked verified follow-up rendering so status questions and change-summary
  follow-ups start with one direct status sentence instead of the recursive
  internal preamble.
- Added a small normalization step that strips prior generated status
  preambles before building the next verified follow-up answer.
- Added unique verified-detail extraction for succeeded/failed sections and
  remaining static verification problems, preventing repeated problem lines
  from nesting across follow-up turns.
- Preserved T19 truthfulness: the latest structured verified outcome remains
  authoritative and model-authored completion claims are ignored.
- Added deterministic e2e scenario 64 for repeated status follow-ups.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest$VerifiedFollowUpSummaries" --no-daemon`
  -> FAIL, expected failures because status answers did not start with
  `Partially.` and repeated prior generated status preambles.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest$VerifiedFollowUpSummaries" --no-daemon`
  -> PASS.
- Focused executor suite:
  `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon`
  -> PASS.
- Focused e2e:
  `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repeatedStatusFollowupDirectUnduplicated" --no-daemon`
  -> PASS.
- Regression e2e after wording adjustment:
  `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.partialFollowupSummaryUsesVerifiedHistory" --no-daemon`
  -> PASS.
- `./gradlew.bat e2eTest --no-daemon` -> PASS.
- `./gradlew.bat check --no-daemon` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket changed final-answer truthfulness, so focused
red/green unit coverage, focused deterministic e2e, full `e2eTest`, hard gate
`check`, and installed manual Talos verification were run. Candidate loop was
not run; no versioned candidate was declared and `CHANGELOG.md` was not
updated.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, one non-technical BMI mutation
prompt, approval `a`, two status follow-ups, and `/q` into the installed Talos
CLI.

Workspace:
`local/manual-workspaces/T26/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Hi, I don't really know coding. I have this little BMI page here and it only shows a title. Can you make it actually work for me? Please update the local files. Use file tools; do not just show code.
```

Status prompts:
```text
did you make the changes?
is it working now?
```

Approval choice:
`a`

Observed tools:
Mutation turn used `talos.list_dir`, `talos.read_file`, `talos.edit_file`.
Both status turns exposed read-only tools in trace and did not call mutating
tools.

Files changed:
`index.html` was edited in `local/manual-workspaces/T26/`.

Output file:
`local/manual-testing/T26-output.txt`

Pass/fail:
PASS.

Notes:
The initial mutation remained incomplete:
`HTML references missing JavaScript file: script.js` and
`Calculator/form task is missing a result output element`.
Both follow-up answers started directly with:
`No. The previous verified outcome says the task is not complete.`
They listed the two unresolved static verification problems once and did not
repeat `The previous verified result says...`. Both follow-ups were
`VERIFY_ONLY`, `mutationAllowed=false`.

## Known Follow-Ups

- T26 intentionally improves wording and deduplication only. It does not run
  fresh broad verification or mutate on status questions.

## Commit

Commit message:
`T26: make status follow-ups direct and unduplicated`

Commit hash:
Recorded in final handoff after commit creation.
