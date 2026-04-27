# [done] Ticket: Centralize Execution Outcome And Truth Handling

Date: 2026-04-24
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
Related runtime-history tickets:
- `work-cycle-docs/tickets/done/talos-post-edit-truthfulness-and-analysis.md`
- `work-cycle-docs/tickets/done/talos-streaming-no-tool-explicit-mutation-and-selector-grounding.md`
- `work-cycle-docs/tickets/done/talos-post-denial-mutation-recovery.md`

## Why This Ticket Exists

Talos has accumulated many good runtime truth protections, but they are still
primarily expressed as helper branches inside `AssistantTurnExecutor`.

Examples already present:
- synthesis retry
- missing-mutation retry
- inspect-completeness retry
- selector-grounding override
- denied-mutation summary
- partial-mutation summary
- false-mutation-claim annotation
- streaming no-tool truthfulness handling

These protections are valuable, but the architectural review found the core
problem clearly:

Talos has discipline mechanisms, but not yet a small central execution model
that explains them.

## Problem

Today, final-turn truth handling is still too dependent on:
- scattered helper functions
- helper ordering inside `AssistantTurnExecutor`
- local detection heuristics
- post-hoc answer shaping

This creates three problems:

1. the runtime is harder to reason about than it should be
2. adding one more truth fix risks another patch branch
3. later architecture work like phases and verification has no central outcome
   object to build on

## Goal

Create a small central runtime outcome model that classifies what actually
happened in a turn and becomes the main source for final-answer shaping.

## Important Naming Note

Do not jump straight to a grand `TaskOutcome` abstraction if that implies a
planner-heavy or workflow-heavy runtime.

The current Talos runtime is turn-based.
The safer first abstraction is something like:
- `ExecutionOutcome`
- `TurnOutcome`
- or similarly narrow runtime terminology

The important thing is centralization, not the word.

## Ordering Note

The architecture source docs place runtime phase work before richer execution
modeling.

This ticket deliberately lands first anyway because the current executor truth
logic is already too scattered to cleanly receive phase/verifier behavior.

So this ticket is a controlled runtime cleanup step before phase policy, not a
claim that outcome modeling is more important than phases in principle.

## Desired End State

At the end of a turn, Talos should be able to explain through one structured
object:

- whether the turn was read-only or mutating
- whether mutations succeeded, failed, or were denied
- whether the answer was grounded or ungrounded
- whether verification passed, failed, or was not run
- whether the final status is complete, partial, blocked, or advisory-only

That object should then drive final answer shaping more than scattered helper
branches do today.

Important limitation:
- any verification-related field in this ticket is provisional only
- until tickets 3 and 4 land, verification means "not run / unavailable" unless
  an already-existing local check explicitly produced a result
- this ticket must not define final completion semantics that depend on a
  future verifier

## Scope

### In scope

- centralize current truth/result classification
- capture denied / partial / no-tool / ungrounded / false-claim outcomes in one place
- reduce scattered executor-specific answer shaping where possible
- prepare the runtime for later phase policy and verification work

### Out of scope

- introducing a workflow planner
- browser/shell/test-runner verification
- UI/CLI explainability commands
- heavy task decomposition abstractions

## Proposed Direction

### 1. Create a central outcome model

Likely fields:
- contract or intent summary for the turn
- tool outcomes
- mutating successes
- denied mutations
- warnings / truth flags
- verification result if any
- completion status

### 2. Move current post-tool truth branches behind that model

The runtime should still be able to:
- summarize denied mutation
- summarize partial success
- suppress false applied-work claims
- distinguish grounded vs ungrounded evidence answers

But those should be conclusions of the outcome model, not only independent
helper behavior.

This explicitly includes the streaming no-tool path.

The current streaming no-tool branch is not an optional side case. It is one of
the important remaining runtime truth gaps, so it must be represented in the
same central outcome model as tool-loop outcomes.

### 3. Keep the implementation narrow

This should be a runtime simplification ticket, not a doctrine rewrite.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/*`
- possibly a new runtime outcome class/package

## Open Design Questions

1. How much of the current helper ordering should survive unchanged initially?
2. Should the outcome model live in `runtime` or `cli/modes`?

## Test / Verification Plan

### Required regressions

- denied mutation turn
- partial mutation turn
- no-tool fabricated mutation narration
- grounded selector mismatch answer
- no-tool ungrounded evidence answer

### Stability checks

- current mutation-intent guard behavior remains unchanged
- current approval-denial truthfulness remains unchanged

### Scope handoff to later tickets

- remaining open scope in
  `work-cycle-docs/tickets/done/talos-post-edit-truthfulness-and-analysis.md`
  should be considered subsumed once this ticket centralizes the current
  truth/outcome logic successfully

## Acceptance Criteria

- final-turn truth handling is driven by a central structured outcome model
- major existing truth-layer regressions remain covered
- the executor becomes easier to reason about, not more layered
- later phase/verifier work has a central outcome seam to attach to
