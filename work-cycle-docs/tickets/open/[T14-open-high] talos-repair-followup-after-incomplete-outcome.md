# [open] Ticket: Repair Follow-Ups Must Use Prior Incomplete Outcome
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`
- `work-cycle-docs/tickets/done/talos-partial-mutation-static-verification-followup.md`
- `work-cycle-docs/tickets/done/talos-static-verification-failure-repair-or-downgrade.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed repair follow-ups being treated as read-only prose:

```text
but nothing happened, nothing changed
no no changes happened as I see it. can you please try one more time?
```

Talos printed code blocks and instructions instead of continuing the failed
workspace repair.

## Problem

Talos currently classifies each turn mostly from the latest user message. It
does not sufficiently use the previous `TaskOutcome` when deciding whether a
follow-up is a repair continuation.

After a failed or partial mutation, user dissatisfaction or retry language often
means:

```text
continue the previous task and fix the incomplete result
```

But status questions such as "did you make the changes?" must remain
verify-only. This ticket must keep that boundary explicit.

## Goal

When the previous outcome was incomplete or failed, natural repair follow-ups
should become apply-capable only when the user expresses dissatisfaction,
retry, or an imperative repair request.

## Scope

### In scope

- Add repair-continuation detection using previous verified outcome context.
- Preserve read-only behavior for status questions.
- Preserve approval gating for all resulting mutations.
- Add deterministic transcript-shaped tests.

### Out of scope

- Full autonomous background continuation.
- Multi-agent task memory.
- Applying changes without explicit user repair/continue intent.

## Proposed Work

1. Define a small repair-follow-up classifier that considers:
   - latest user prompt,
   - previous task type,
   - previous outcome status: partial, failed, incomplete.
2. Treat prompts like "nothing happened", "try again", "fix it", and
   "it still does not work" as repair continuations when prior outcome permits.
3. Treat prompts like "did you make the changes?" as verify/status questions,
   not repair continuations.
4. Expose the inherited expected targets from the prior task where safe.
5. Add tests for both positive and negative cases.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/session/` or existing session/turn trace code
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for repair-follow-up classification.
- E2E scenario: failed multi-file web task followed by "nothing changed, try
  one more time" must expose write/edit tools.
- E2E scenario: failed multi-file web task followed by "did you make the
  changes?" must not expose write/edit tools.

## Acceptance Criteria

- Repair follow-ups after incomplete outcomes can continue the previous task.
- Plain status questions remain read-only/verify-only.
- Expected targets from the previous task are available to verification when a
  repair continuation is accepted.
- No mutation happens without approval.
