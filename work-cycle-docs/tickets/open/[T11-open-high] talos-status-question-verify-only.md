# [open] Ticket: Status Questions Must Verify, Not Mutate
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos mutating the workspace after the user asked a status
question:

```text
did you make the changes?
```

Talos created `scripts.js` containing only placeholder text. This is a trust and
safety regression: a question about whether work happened is not permission to
write.

## Problem

`MutationIntent` still contains broad markers such as `make the`, and
`TaskContractResolver` can classify a status question like "did you make the
changes?" as mutation-capable. The model then receives write tools and may apply
changes on a verification turn.

This is especially dangerous after partial or failed mutation turns because the
conversation context contains the original task, but the latest user prompt is
asking for inspection/status, not another apply attempt.

## Goal

Status questions about previous changes must default to `VERIFY`/`INSPECT`
behavior:

```text
"did you make the changes?"
-> read/inspect/status only; no mutation tools

"what changed?"
-> report the previous verified outcome or inspect files; no mutation tools

"did you make the changes? if not, make them now"
-> verify first; apply only if verification proves incomplete and the user
   explicitly requested conditional apply
```

## Scope

### In scope

- Add deterministic status-question handling before broad mutation markers.
- Prevent `make the` / `make it` style markers from matching past-tense status
  questions.
- Ensure the active contract exposes only read/verify tools for plain status
  questions.
- Preserve apply-capable behavior for explicit repair imperatives such as
  "nothing changed, fix it now".
- Add regression coverage for transcript-shaped prompts.

### Out of scope

- Implementing a full multi-turn planning engine.
- Adding new tools.
- Weakening mutation approval requirements.

## Proposed Work

1. Add status-question detection to `TaskContractResolver` or
   `MutationIntent` before broad mutation matching.
2. Classify plain status questions as `VERIFY_ONLY` or another read-only
   contract that requires evidence.
3. Add tests proving these prompts do not allow mutation:

   ```text
   did you make the changes?
   did you update the files?
   what did you change?
   why did nothing change?
   ```

4. Add tests proving repair prompts still allow mutation:

   ```text
   nothing changed, fix it now
   it still does not work, update the files
   ```

5. Add one deterministic E2E scenario where the model attempts a write on a
   status question and phase/contract policy blocks it.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

- Run focused unit tests for task contract and mutation intent.
- Run the new JSON-backed scenario.
- Run `./gradlew.bat e2eTest` before marking done.
- Manual retest the transcript slice with `/debug trace`.

## Acceptance Criteria

- `did you make the changes?` has `mutationAllowed=false`.
- Write/edit tools are not exposed for plain status questions.
- If the model still emits a write tool call on a status question, phase policy
  blocks it before approval.
- The answer reports observed state or previous verified outcome instead of
  creating files.
- Explicit repair imperatives remain mutation-capable.
