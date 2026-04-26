# [in-progress] Ticket: Follow-Up Summary Contradicts Partial Verification
Date: 2026-04-26
Priority: high
Status: in-progress
Architecture references:
- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/tickets/talos-post-edit-truthfulness-and-analysis.md`
- `local/tickets/talos-minimal-task-outcome.md`

## Why This Ticket Exists

Execution outcome centralization now replaces the immediate mutation turn with
a truthful partial verification summary. The installed debug run exposed a
multi-turn continuity gap: the next user asks for a plain-English summary, and
the model reverts to claiming completion.

## Problem

Mutation turn result:

```text
[Partial verification: static checks failed - HTML does not link JavaScript file: `script.js`;
CSS references missing class selectors: `.cta-button`; JavaScript references missing class
selectors: `.cta-button`]
```

Follow-up prompt:

```text
Can you summarize what changed in plain English?
```

Observed follow-up answer:

```text
Added a Listen Now Button...
Updated the Text...
The changes were made directly within the index.html file...
```

Actual file state after the run:

- `index.html` had only a punctuation/copy tweak.
- no `Listen now` button existed.
- `script.js` was still not linked.
- `.cta-button` was still missing from HTML.

The latest verified outcome was present in conversation history, but the
follow-up answer was generated as generic prose instead of from the last
verified task outcome.

## Goal

When the user asks a follow-up summary after a partial mutation, Talos should
summarize the verified outcome, not the model's intended plan.

## Scope

### In scope

- Preserve structured `TaskOutcome` / `ExecutionOutcome` facts for follow-up
  turns.
- Detect follow-up summary prompts such as "what changed?" and "summarize what
  changed".
- Answer from the last verified mutation outcome when present.

### Out of scope

- Long-term project memory redesign.
- Claiming browser-level verification.

## Proposed Work

1. Add a session-visible structured summary of the previous mutation outcome.
2. Add a small follow-up intent classifier for "what changed" questions.
3. Route those turns to deterministic outcome summarization when the last turn
   was a mutation with partial or failed verification.
4. Add a scenario with:

   ```text
   mutation partial -> "Can you summarize what changed in plain English?"
   ```

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/TurnRecord.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI check:

```text
/debug trace
<prompt that causes partial mutation>
a
Can you summarize what changed in plain English?
/last trace
```

## Acceptance Criteria

- Follow-up summaries name only verified changes.
- Remaining static verification problems are mentioned plainly.
- Talos does not claim a missing button was added.
- Talos does not collapse a partial mutation into a completed task.

## Progress Notes

Added a deterministic follow-up guard in `AssistantTurnExecutor`: when the user
asks "what changed?" and prior assistant history contains static/partial
verification text, Talos summarizes that verified outcome instead of accepting a
fresh unsupported model claim.

Covered by `AssistantTurnExecutorTest`.

Remaining work before closing:

- Add a JSON-backed multi-turn scenario or equivalent harness support for
  `partial mutation -> summarize what changed`.
- Run an installed CLI partial-mutation transcript after the scenario is in
  place.
