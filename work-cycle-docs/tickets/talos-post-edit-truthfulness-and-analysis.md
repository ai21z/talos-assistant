# [done] Ticket: High Priority Follow-Up - Post-Edit Truthfulness And Analysis Accuracy

Date: 2026-04-23
Priority: high
Status: done
Depends on / references:
- `work-cycle-docs/tickets/talos-mutation-intent-guard.md`
- branch context: `fix/ticket-talos-auto-mutation-guard`

## Why This Is A Separate Ticket

The mutation-intent guard materially improved Talos:
- read-only prompts no longer drift into unsolicited mutation attempts
- explicit edit flows now stay inside a safer runtime envelope

But the latest manual run exposed two remaining defects that are related, but
not the same bug:
- Talos can still summarize a mutation turn inaccurately after partial failure
- Talos can still produce incorrect grounded analysis even after reading the
  relevant files

These are both trust bugs. They deserve a separate high-priority ticket
because the workspace-safety fix is no longer the main issue in this flow.

## Problem 1: Post-Edit Truthfulness Failure

Observed in the latest run:

1. User asked Talos to inspect `index.html` and fix it.
2. Talos read the file and proposed multiple mutations.
3. The first `edit_file` call failed because `old_string` did not match the
   actual file content.
4. Later edits and a CSS write succeeded.
5. Talos then told the user the title update had been completed, even though
   that specific edit had failed.

That means Talos still overstates what happened in a partial-success turn.

### Why this matters

- the user cannot trust the final summary without manual inspection
- partial mutation failure is normal and should be described precisely
- this undermines the value of the runtime audit and verification messages

## Problem 2: Grounded Analysis Accuracy Failure

Observed earlier in the same run:

1. User asked whether HTML classes and IDs matched CSS / JavaScript selectors.
2. Talos correctly read `index.html`, `style.css`, and `script.js`.
3. Talos then claimed there were no mismatches.
4. The answer asserted that `.cta-button` was present in HTML and JavaScript,
   but the shown HTML excerpts did not support that claim.

So the tool usage was correct, but the synthesis over the tool outputs was not.

### Why this matters

- read-only analysis is supposed to be Talos' safest mode
- if grounded inspection still hallucinates facts, user trust remains weak
- this can mislead the user into approving or planning the wrong follow-up work

## Likely Root Cause Areas

### A. Final answer synthesis is not constrained tightly enough by tool outcomes

Talos appears able to summarize planned changes instead of successful changes.
That suggests the final answer path is not distinguishing clearly enough
between:
- proposed mutations
- attempted mutations
- successful mutations
- failed mutations

### B. Read-only analysis answers are still too model-inferred

Even after reading the right files, Talos may still fill gaps from prior
expectations instead of only from retrieved content. In practice that means:
- inferred selectors can leak into the answer
- stale assumptions can survive despite tool evidence
- the answer can sound grounded while being partially fabricated

## Desired Behavior

### For mutation turns

Talos should report only verified outcomes.

If a turn partially succeeds:
- successful edits/writes should be named accurately
- failed edits should be called out explicitly
- the final summary must not claim that a failed change was applied

### For read-only analysis turns

Talos should make a clear distinction between:
- facts directly observed in tool output
- inferences
- unknowns

If a class, ID, selector, or element was not actually observed, Talos should
not present it as a fact.

## Proposed Solution Direction

### 1. Add stronger post-tool synthesis constraints

The answer-synthesis path should receive structured facts about tool outcomes:
- which tool calls succeeded
- which failed
- which files were actually mutated
- what mutation verification said

Then the final answer should be based on that structured result set, not just
the model's recollection of its own prior plan.

### 2. Add a claim-vs-evidence discipline for read-only analysis

When the user asks an inspection question:
- encourage or require answers to be grounded in observed tool output
- if the model is uncertain, it should say so
- if a claim was not observed, it should not be stated as fact

This may be partly prompt-related, but it should be solved first as a runtime
and answer-construction problem. Prompt tuning can reinforce the behavior, but
it should not be the primary safety or truthfulness mechanism.

### 3. Consider targeted executor annotations

For partial mutation turns, the executor could prepend or inject a short factual
note such as:
- one or more requested edits failed
- only these files were actually modified

That would reduce the chance of a polished but false summary.

## Open Questions

1. Should post-tool final answers be generated from a structured execution
   summary instead of raw conversation state?
2. Should read-only analysis answers be explicitly marked when they contain
   inference instead of direct observation?
3. Should the executor detect contradiction between claimed changes and
   successful mutation results?
4. Is there already enough audit data to drive this, or do we need a more
   explicit per-turn mutation result summary object?

## Test Plan

### Mutation truthfulness

- scenario: multiple mutation calls where one fails and later ones succeed
- expected:
  - final answer names only successful changes
  - failed title change is called out as failed
  - no claim says a failed edit was applied

### Analysis grounding

- scenario: HTML/CSS/JS selector mismatch inspection where one selector exists
  only in CSS/JS and not in HTML
- expected:
  - Talos identifies the mismatch
  - Talos does not claim the selector exists in HTML unless it was observed

### Manual regression

- repeat the `horror-synth-site` transcript shape from
  `local/manual-testing/test-output`
- verify:
  - read-only turns stay read-only
  - analysis is grounded
  - explicit fix turns summarize only actual applied changes

## Acceptance Criteria

- partial-success edit turns produce truthful summaries
- failed edits are never reported as completed
- a failed title edit is not summarized as applied when later edits succeed
- read-only analysis answers do not present unobserved selectors/elements as fact
- the latest `horror-synth-site` regression shape is covered by tests

## Completion Notes

This ticket is now satisfied by the runtime discipline slices that landed after
it was opened:

- `ExecutionOutcome` centralizes post-tool truth shaping.
- partial mutation turns replace the assistant summary with structured success
  and failure facts.
- selector mismatch grounding corrects unsupported no-mismatch prose from
  workspace evidence.
- `StaticTaskVerifier` prevents a selector repair from being reported as
  statically verified when `.cta-button` remains missing.
- `TaskOutcome` carries structured mutation and verification state for later
  policy work.

The acceptance cases are covered by:

```text
src/e2eTest/resources/scenarios/10-selector-mismatch-grounded.json
src/e2eTest/resources/scenarios/11-partial-mutation-summary-truthful.json
src/e2eTest/resources/scenarios/17-static-verifier-selector-fails-after-wrong-edit.json
src/e2eTest/resources/scenarios/18-static-verifier-selector-passes-after-cta-fix.json
src/e2eTest/resources/scenarios/19-static-verifier-partial-mutation-not-verified-complete.json
src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java
src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java
```

Manual installed Talos verification has repeatedly confirmed the horror-synth
selector-mismatch flow: the model may still initially claim no mismatch, but
Talos corrects the final answer from workspace evidence and keeps denied writes
truthful.
