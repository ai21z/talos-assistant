# [T22-open-high] Ticket: Mutation Contract Must Recognize Overwrite / Repair Phrasing
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T14-done-high] talos-repair-followup-after-incomplete-outcome.md
- work-cycle-docs/tickets/done/[T20-done-high] talos-scoped-target-limiter-mutation-intent.md

## Why This Ticket Exists

Manual Talos testing with qwen2.5-coder:14b showed that the live model can understand a user request as a file mutation and emit `write_file`, while Talos classifies the same turn as read-only/diagnostic and blocks the writes.

This violates the task-contract discipline: a natural explicit local-operator request should not expose a read-only contract when the user is clearly asking Talos to overwrite or repair files.

## Problem

Reproduced transcripts:

- `local/manual-testing/deep-review/bmi-broken-b-transcript.txt`
- `local/manual-testing/deep-review/bmi-empty-c-writefile-repair-transcript.txt`
- `local/manual-testing/deep-review/route-mutation-phrasing-transcript.txt`

Observed examples:

- Prompt: `Overwrite these three files to make a working BMI calculator: index.html, styles.css, scripts.js. Use talos.write_file for all three.`
  - Model attempted `write_file`.
  - Trace: `contract: READ_ONLY_QA mutationAllowed=false`.
  - Writes were blocked by `task-contract read-only denied talos.write_file`.

- Prompt: `Overwrite index.html with a corrected complete version instead of using edit_file... Use write_file for index.html.`
  - Model attempted `write_file`.
  - Trace: `contract: DIAGNOSE_ONLY mutationAllowed=false`.
  - Writes were blocked by read-only policy.

Source inspection suggests a likely gap:

- `MutationIntent.CORE_MUTATION_VERBS` includes `rewrite` and `replace` but not `overwrite`.
- `TaskContractResolver.CREATE_MARKERS` includes `create`, `write`, `build`, `generate`, etc., but not `overwrite`, `rewrite`, or `replace`.
- Some repair prompts containing diagnostic words can still collapse to `DIAGNOSE_ONLY` despite explicit file write intent.

## Goal

Natural mutation requests using `overwrite`, `rewrite`, `replace`, and explicit `use write_file` repair language should resolve to a mutation-allowed `TaskContract` when scoped to workspace files.

## Scope

In scope:
- Extend deterministic mutation intent coverage for common local-operator repair verbs.
- Ensure explicit target-file overwrite/replace/rewrite requests become `FILE_EDIT` or `FILE_CREATE` with `mutationAllowed=true`.
- Add focused unit tests for the reproduced phrasings.
- Add at least one transcript-shaped e2e scenario where the model emits write tools and Talos must not block them as read-only.

Out of scope:
- Browser/runtime execution.
- Broad natural-language intent rewrite.
- Weakening scoped negation protections from T20.
- Allowing mutation for pure status questions such as `did you make the changes?`.

## Proposed Work

- Update `MutationIntent` and/or `TaskContractResolver` so `overwrite`, `rewrite`, `replace`, and explicit write-file repair requests are mutation-positive.
- Keep status-question protections from T11/T19 intact.
- Keep scoped target limiters from T20 intact.
- Add tests proving:
  - `Overwrite index.html... Use write_file` is mutation-allowed.
  - `Overwrite these three files...` is mutation-allowed.
  - `Replace index.html with a corrected complete version` is mutation-allowed.
  - `did you make the changes?` remains verify-only.
  - `do not change anything` remains read-only.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit tests for `MutationIntent` and `TaskContractResolver`.
- Focused e2e scenario for overwrite/repair phrasing with mutating tools.
- Full `./gradlew.bat e2eTest`.
- Manual Talos check in a small web workspace:
  - Prompt with `overwrite`.
  - Confirm trace is mutation-allowed.
  - Confirm write approval appears.
  - Confirm no read-only tool block happens.

## Acceptance Criteria

- Reproduced overwrite/repair prompts classify as mutation-allowed.
- Mutating tool calls are not blocked by read-only contract for those prompts.
- Pure status questions remain verify-only/read-only.
- Scoped negation still limits targets without cancelling the allowed target.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `bmi-broken-b-transcript.txt`: explicit `Overwrite these three files... Use talos.write_file for all three` was read-only and blocked write calls.
- `bmi-empty-c-writefile-repair-transcript.txt`: explicit `Overwrite index.html... Use write_file for index.html` was diagnostic/read-only and blocked write calls.

Additional non-technical phrasing evidence on 2026-04-28:

- `local/manual-testing/deep-review-2/nondev-bmi-empty-transcript.txt`
  - Prompt: `I have an empty folder. Can you make me a simple BMI calculator webpage here? I am not technical, I just want a page I can open and use.`
  - Observed: model attempted `write_file`, but trace was `contract: READ_ONLY_QA mutationAllowed=false`.
  - Blocked reason: `task-contract read-only denied talos.write_file`.
  - User-visible answer then claimed Talos could not create/modify files and gave copy/paste instructions.
- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`
  - Prompt: `Hi, I don't really know coding. I have this little BMI page here and it only shows a title. Can you look at it and make it actually work for me?`
  - Observed: trace was correctly `FILE_EDIT mutationAllowed=true`, but the model asked the non-technical user to provide the HTML path instead of using workspace tools to locate `index.html`.
  - Follow-up `I opened it and it still does not feel like a working calculator... Can you fix the files in this folder for me?` drifted to `READ_ONLY_QA` and again asked for project structure.

These examples show two related intent issues:

- Some regular-user creation phrasing (`make me a ... webpage`) is not mutation-positive enough.
- Even when the contract is mutation-positive, Talos may accept a no-tool path/context request instead of forcing local workspace inspection.
