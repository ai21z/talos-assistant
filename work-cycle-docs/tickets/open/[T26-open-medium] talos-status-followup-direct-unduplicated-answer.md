# [T26-open-medium] Ticket: Status Follow-Up Should Be Direct And Unduplicated
Date: 2026-04-28
Priority: medium
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
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
