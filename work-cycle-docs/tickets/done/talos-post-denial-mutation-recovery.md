# [done] Ticket: Post-Denial Mutation Recovery Still Degrades Into Manual-Update Prose

Date: 2026-04-24
Priority: high
Status: done
Branch context: `fix/ticket-talos-auto-mutation-guard`
References:
- `work-cycle-docs/tickets/done/talos-mutation-intent-guard.md`
- `work-cycle-docs/tickets/done/talos-post-edit-truthfulness-and-analysis.md`
- `work-cycle-docs/tickets/done/talos-streaming-no-tool-explicit-mutation-and-selector-grounding.md`
- manual run transcript: `local/manual-testing/test-output`

## Why This Is The Next Ticket

The latest installed-CLI manual run confirms that two earlier fixes are now
behaving as intended:

- the selector-grounding override no longer reports CSS hex colors as missing
  HTML IDs
- the explicit-mutation streaming no-tool escape did not reproduce on the
  tested explicit edit prompt, because the model entered the tool loop and
  issued real tool calls

But that same run exposed a new dominant failure mode after the user denies
write approval:

1. Talos enters the tool loop correctly.
2. Talos attempts legitimate mutating tool calls.
3. Approval is denied.
4. Talos continues reasoning inside the loop.
5. Talos degrades into “manually update the file with this content” prose,
   often with malformed or incorrect file contents.
6. The missing-mutation retry can then re-prompt and trigger another failed
   `write_file` attempt.

This is a distinct trust/runtime problem. It is no longer about unsolicited
mutation starts. It is now about what Talos does after a valid mutation attempt
is explicitly denied by the user.

## Observed Failure Shape

In the installed CLI run:

1. User asked:
   - `I think the html is completely wrong. Can you fix it?`
2. Talos entered the tool loop and read the relevant files.
3. Talos attempted `edit_file` calls against `style.css`, `script.js`, and
   later `index.html`.
4. The user denied approval.
5. Talos recovered poorly:
   - it proposed new edit/write attempts
   - it emitted malformed replacement content
   - it eventually told the user to manually replace `index.html` with
     assistant-generated content
6. The missing-mutation retry then fired and caused another failed
   `write_file` attempt before ending in more manual-update prose

That means Talos still behaves as though “a file update plan” is the right
answer even after the user has explicitly refused the write.

## What Is Wrong About That Behavior

Once a user denies approval on a mutation turn, Talos should not continue
acting like:

- “I’ll manually update the file content”
- “replace the file with this content”
- “here is the corrected file; paste this in”

unless the user explicitly asked for code-as-text instead of tool-backed
mutation.

In the normal local-workspace CLI flow, post-denial behavior should become one
of these:

- explain that no file was changed
- summarize what would need to change if the user wants to try again
- ask what the user wants to do differently next
- continue in read-only advisory mode

What it should not do is keep simulating a completed file update after the user
said no.

## Root Cause Hypothesis

The earlier fixes correctly hardened:

- read-only mutation intent
- text-path synthetic tool-result handling
- selector grounding
- streaming no-tool mutation narration

But after an approval denial inside the real tool loop, Talos is still allowed
to treat the denied mutation as a planning problem to continue solving.

Contributing factors likely include:

1. denial tool-result wording still leaves too much room for continued write
   pursuit
2. missing-mutation retry does not distinguish:
   - “no mutation happened because the model forgot”
   - from
   - “no mutation happened because the user explicitly denied it”
3. post-denial final-answer handling does not replace simulated applied-work
   prose with a factual “no change was made” outcome

## Desired Behavior

For a mutation turn where approval is denied:

- Talos must not claim or simulate that the file was changed
- Talos must not present assistant-authored replacement file content as though
  the next expected step is manual copy/paste
- missing-mutation retry should not fire if the absence of mutation is caused
  by explicit user denial
- the final answer should clearly state:
  - no file was changed
  - approval was denied
  - Talos can help further if the user wants a different approach

## Proposed Solution Direction

### 1. Treat approval denial as a terminal mutation outcome for that turn

Once a mutating tool call is denied by the user:

- record that denial distinctly in the turn outcome
- suppress any retry logic whose purpose is “the user asked for a change but no
  mutation happened”

This should be true even if the model keeps emitting more write attempts.

### 2. Add a post-denial truthfulness layer

If a turn contains:

- explicit mutation intent
- zero successful mutating tools
- one or more denied mutating tools

then the final answer should be replaced or strongly overridden with a factual
post-denial summary such as:

- no files were changed because the requested write was not approved
- here is what Talos was trying to change
- ask the user whether to retry or take a read-only approach

### 3. Prevent manual-update prose from surviving as the final answer

If the answer after denial contains replacement-file prose such as:

- `Updated index.html`
- `replace its content with`
- `manually update the file`
- fenced full-file content presented as the next action

Talos should not let that stand as the final answer in the normal CLI mutation
flow after denial.

## Important Non-Goal

Do not weaken the existing approval model.

The problem is not that Talos asked for approval. The problem is that after the
user denied approval, Talos kept behaving like a silent file-update assistant
instead of closing the turn truthfully.

## Open Questions

1. Should post-denial handling live in `AssistantTurnExecutor`, in the tool
   loop, or in `TurnProcessor` / tool-result shaping?
2. Should denied mutating calls be counted separately from generic failed
   mutating calls in the loop result?
3. Should manual-update prose be replaced wholesale, or annotated plus
   summarized away?
4. Should denial wording itself be changed to more strongly push the model into
   advisory/read-only closure?

## Test Plan

### Post-denial mutation regression

- scenario:
  - user explicitly requests a file fix
  - model issues mutating tool calls
  - approval is denied
- expected:
  - no file changes are reported as applied
  - no manual replacement-file prose survives unchanged as the final answer
  - final answer states that no file was changed because approval was denied

### Missing-mutation retry suppression

- scenario:
  - explicit mutation request
  - one or more mutating tool calls denied by approval
  - zero mutating tool successes
- expected:
  - missing-mutation retry does not fire

### Guard regression

- existing explicit mutation flows still reach approval
- existing read-only mutation guard remains unchanged

## Acceptance Criteria

- after approval denial, Talos no longer ends the turn with simulated manual
  file-update prose
- missing-mutation retry does not fire when the lack of mutation is explained
  by explicit user denial
- final answer on denied mutation turns truthfully states that no file was
  changed
- the installed-CLI transcript shape from `local/manual-testing/test-output`
  is covered by tests
