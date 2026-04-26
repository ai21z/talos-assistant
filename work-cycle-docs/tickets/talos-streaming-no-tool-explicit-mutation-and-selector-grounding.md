# [done] Ticket: Streaming No-Tool Explicit Mutation Escape And Selector Grounding Fix

Date: 2026-04-24
Priority: high
Status: done
Branch context: `fix/ticket-talos-auto-mutation-guard`
References:
- `work-cycle-docs/tickets/talos-mutation-intent-guard.md`
- `work-cycle-docs/tickets/talos-post-edit-truthfulness-and-analysis.md`
- manual transcript: `local/manual-testing/test-output`

## Why This Is A New Ticket

Recent fixes materially improved the tool path:
- unsolicited mutation attempts on read-only turns are blocked before approval
- partial-success mutation summaries are truth-backed
- selector-mismatch analysis is overridden from actual workspace files once the
  turn enters the tool loop

But the latest manual run exposed two remaining defects that are both runtime
issues and both still high priority:

1. the selector-grounding override is misclassifying CSS color literals as ID
   selectors
2. explicit edit requests can still escape through the streaming no-tool path,
   where Talos only annotates fabricated mutation prose instead of forcing a
   tool-backed path

These are distinct from the earlier mutation-intent guard ticket. That guard is
working as designed for read-only turns. The remaining failures are:
- one false-positive deterministic analysis in the tool path
- one insufficiently enforced explicit-mutation path in the streaming no-tool
  branch

## Problem 1: Selector Grounding False Positives

Observed in the latest run:

1. The user explicitly asked Talos to check the workspace and inspect selector
   mismatches.
2. The model emitted three `talos.read_file` calls for `index.html`,
   `style.css`, and `script.js`.
3. Talos executed those tools successfully.
4. Talos then replaced the model answer with the deterministic selector
   grounding override.
5. The override reported:
   - `CSS references missing ID selectors: #ff4500, #ff6347, #ffffff`

That result is wrong. Those strings are CSS color literals, not HTML ID
selectors.

### Root Cause

In `AssistantTurnExecutor`, the deterministic selector analysis currently uses:

- `CSS_ID_SELECTOR = "#([A-Za-z_][A-Za-z0-9_-]*)"`

That regex matches:
- real CSS ID selectors like `#hero`
- hex color literals like `#ff4500`

So the deterministic override is currently unsound for any stylesheet that
contains hex colors.

### Why This Matters

- this is a Talos/runtime bug, not just model drift
- the deterministic override is supposed to increase trust, not introduce
  false positives
- a false deterministic answer is more damaging than a model guess, because it
  appears authoritative

## Problem 2: Explicit Mutation Requests Still Escape On The Streaming No-Tool Path

Observed in the latest run:

1. The user explicitly asked:
   - `I think the html is completely wrong. Can you fix it?`
2. The model stayed on the streaming no-tool path.
3. It narrated completed HTML updates without calling `talos.edit_file` or
   `talos.write_file`.
4. Talos prepended the new streaming mutation annotation:
   - `Truth check: the response below narrates completed file changes...`
5. But Talos still let the fabricated mutation prose pass through and enter
   history.

The same thing happened again on:
- `edit it please`

### What This Means

The current streaming no-tool fix is diagnostically useful but behaviorally too
weak for explicit mutation turns.

Today:
- read-only no-tool fabrication is annotated
- mutation-style no-tool narration is annotated
- but explicit edit requests are still not forced onto a tool-backed path

So Talos can still behave like:
- “Here is the updated `index.html`...”
- while having made zero real tool calls

### Why This Matters

- explicit edit prompts should not settle for “annotated fiction”
- fake applied-change prose still contaminates conversation history
- later turns can build on those fabricated changes
- the user still has to manually push Talos toward real tool usage

## Important Clarification About The Mutation Guard

In the same transcript, a later prompt said:

- `but you need to call the edit tool to do that. Why you didnt?`

Talos denied the model's attempted `edit_file` / `write_file` calls on that
turn as read-only.

That denial is correct under the current design:
- the runtime guard uses the current turn's original user request only
- this prompt is a meta-question about behavior, not a direct edit request

So this ticket is not about weakening the mutation-intent guard.

The real failure is earlier:
- explicit edit prompts still stayed on the streaming no-tool prose path
- Talos annotated them but did not correct them

## Desired Behavior

### For selector mismatch analysis

When Talos uses the deterministic selector-grounding override:
- CSS hex colors must not be treated as ID selectors
- only real selector syntax should be reported as selector references
- the override must remain strictly more trustworthy than the model answer it
  replaces

### For explicit mutation turns on the streaming no-tool path

When the current user turn explicitly requests a change:
- Talos should not allow fabricated “updated file” prose to stand as the final
  answer if no mutating tool was called
- annotation alone is insufficient
- Talos should force a corrective path, such as:
  - a retry that explicitly requires tool use
  - a replacement answer that states no file was changed
  - another runtime-centered correction that is at least as strong

## Proposed Solution Direction

### 1. Fix the deterministic selector parser

Make the selector extractor distinguish:
- CSS selectors
- CSS property values

At minimum:
- stop matching color literals as IDs

Preferred direction:
- only extract selector tokens from selector positions, not arbitrary `#...`
  anywhere in CSS text

### 2. Strengthen explicit-mutation handling on the streaming no-tool path

For turns where:
- the user explicitly requested a mutation
- the streamed answer contains mutation-narrative markers
- zero file-mutating tools were called

Talos should do more than annotate.

Reasonable options:
- route into a corrective retry that explicitly tells the model to call
  `edit_file` / `write_file`
- replace the fabricated answer with a factual notice that no file changes were
  applied
- buffer or withhold these high-risk answers long enough to repair them

The key requirement is behavioral, not cosmetic:
- the final answer must no longer silently succeed as fake applied work

### 3. Keep the existing read-only mutation guard intact

Do not loosen:
- current-turn-only intent capture
- explicit mutation requirement for mutating tools

This ticket is about enforcing explicit mutation turns more strongly, not about
making the read-only guard permissive.

## Open Questions

1. Should explicit mutation no-tool correction be retry-based or replacement-based?
2. If retry-based, should the retry happen only for explicit mutation prompts,
   or also for evidence-seeking inspection prompts?
3. Should fabricated no-tool mutation answers be prevented from entering history
   if the correction path fails?
4. Is a small buffered-streaming branch justified here, or is a post-stream
   correction sufficient?

## Test Plan

### Selector-grounding regression

- scenario: CSS file contains hex color literals and one real missing ID/class
- expected:
  - color literals are not reported as ID selectors
  - real missing selectors are still reported

### Explicit mutation streaming no-tool regression

- scenario: user explicitly asks to fix or edit HTML
- model returns streamed no-tool prose like:
  - `### Updated index.html`
  - `Summary of changes`
  - `These changes should...`
- expected:
  - Talos does not allow that fabricated mutation answer to stand unchanged
  - Talos either retries toward real tool use or replaces the answer with a
    factual no-change notice

### Guard stability regression

- scenario: user asks a meta-question like
  - `Why didn't you call the edit tool?`
- expected:
  - mutation guard still treats that turn as read-only
  - no accidental weakening of the current-turn-only policy

## Acceptance Criteria

- selector-grounding override no longer reports hex colors as CSS ID selectors
- deterministic selector analysis remains active for the intended workspace
  mismatch prompt
- explicit edit requests on the streaming no-tool path no longer end in
  fabricated “updated file” prose as the final answer
- read-only mutation guard behavior remains unchanged
- the latest manual transcript shape is covered by tests
