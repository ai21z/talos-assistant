# [T19-open-high] Ticket: Status Follow-up Must Use Verified Outcome
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `work-cycle-docs/tickets/done/[T11-done-high] talos-status-question-verify-only.md`
- `work-cycle-docs/tickets/done/[T14-done-high] talos-repair-followup-after-incomplete-outcome.md`
- `work-cycle-docs/tickets/done/[T15-done-high] talos-readback-verification-wording.md`
- `work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md`

## Why This Ticket Exists

Manual branch review of `ticket/talos-open-ticket-batch-t11-t18` found that
Talos now correctly classifies `did you make the changes?` as a read-only
`VERIFY_ONLY` turn, but still lets the live model produce an overconfident
answer that contradicts the previous verified outcome.

This preserves mutation safety but still violates evidence and outcome
truthfulness. A status question after a partial or failed verified mutation
must answer from the structured previous outcome, not from a fresh model
interpretation of the current files alone.

## Problem

Manual prompt flow:

```text
No no I want a functioning 3-file BMI calculator. Update index.html and
styles.css and create scripts.js. Make it modern and responsive. Use file
tools; do not just show code.
a
did you make the changes?
```

Observed result:

- The mutation turn correctly reported partial verification failure:
  - `styles.css: expected target was not successfully mutated.`
  - `HTML does not link JavaScript file: scripts.js`
  - `HTML defines duplicate IDs: #result`
  - `Calculator/form task is missing a submit/calculate button.`
- The follow-up `did you make the changes?` was correctly traced as:
  - `contract: VERIFY_ONLY`
  - `mutationAllowed=false`
  - read-only native tools only
- But the final answer said:
  - `The workspace now appears to have a functional 3-file BMI calculator.`

Manual evidence:

- `local/manual-testing/branch-review-web-output.txt`
  - partial verification failure around line 101
  - overclaiming status follow-up around line 159

## Goal

Status/change-summary follow-ups after a verified mutation outcome must use
the previous structured outcome as the primary source of truth. If the previous
turn was partial or failed static verification, Talos must not say the task is
complete unless a new verification pass proves that claim.

## Scope

In scope:

- Expand deterministic follow-up handling for prior-change status questions,
  not only narrow "what changed" wording.
- Ensure `did you make the changes?`, `is it done?`, `did it work?`, and
  equivalent status questions summarize the previous verified outcome when one
  exists in history.
- Preserve read-only behavior: no write/edit tools should be exposed for pure
  status questions.
- Add deterministic unit/e2e coverage for partial verification followed by a
  status question.
- Run installed Talos manual verification for the transcript-shaped flow.

Out of scope:

- Browser/runtime execution.
- New shell/browser/test-runner tools.
- Broad task-verifier expansion beyond using existing outcome data.
- Changing approval policy.

## Architecture Invariant

For a prior-change status question, the user-visible answer must not downgrade
or contradict the latest structured mutation outcome in conversation history.

If the latest verified outcome says partial, failed, not verified, or
readback-only, the status follow-up must preserve that status unless Talos
performs a new bounded verification step that changes the outcome.

## Technical Analysis

Likely root seam:

- `AssistantTurnExecutor.deterministicDirectAnswerIfNeeded(...)`
- `AssistantTurnExecutor.verifiedFollowUpSummaryIfNeeded(...)`
- `AssistantTurnExecutor.CHANGE_SUMMARY_FOLLOW_UP_MARKERS`
- `MutationIntent.looksPriorChangeStatusQuestion(...)`
- `TaskContractResolver.fromMessages(...)`

Current behavior appears split:

1. T11/T14 correctly classify prior-change questions as `VERIFY_ONLY`.
2. The native tool surface is read-only, which is good.
3. However, deterministic outcome summary only catches a narrow set:
   - `what changed`
   - `what did you change`
   - `what did you do`
   - `summary of changes`
4. `did you make the changes?` goes through the normal model answer path.
5. The model rereads files and can produce a plausible but wrong completion
   claim, ignoring the previous partial-verification result.

This ticket should prefer a deterministic outcome-summary path over prompt
wording. Prompt text can support the model, but the invariant belongs in
runtime answer shaping.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` or nearby existing tests
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

- Unit test that `did you make the changes?` triggers deterministic previous
  outcome summary when history contains a partial verification answer.
- Unit test that no deterministic "complete" answer is produced when the
  previous outcome says partial/failed.
- Unit test that the same status question remains mutation-disallowed.

E2E:

- JSON scenario:
  - first turn produces partial static verification after a web mutation,
  - second turn asks `did you make the changes?`,
  - expected answer preserves partial/failed status,
  - expected no mutating tools.

Manual:

Use installed Talos against a small incomplete BMI workspace:

```text
/session clear
/debug trace
No no I want a functioning 3-file BMI calculator. Update index.html and styles.css and create scripts.js. Make it modern and responsive. Use file tools; do not just show code.
a
did you make the changes?
```

Expected:

- mutation turn may still be partial if model edits poorly,
- follow-up must not claim completion,
- trace must stay `VERIFY_ONLY`,
- read-only tools only,
- answer must preserve prior static verification failure.

## Acceptance Criteria

- `did you make the changes?` after a partial/failed verified mutation returns
  a truthful status summary from the prior outcome.
- It does not call or expose write/edit tools.
- It does not claim completion when previous static verification failed.
- Existing T11/T14/T15/T16/T18 tests still pass.
- Focused tests, `e2eTest`, `check`, and installed manual verification pass
  before moving the ticket to done.