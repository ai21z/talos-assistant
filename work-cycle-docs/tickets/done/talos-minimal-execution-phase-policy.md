# [done] Ticket: Minimal Execution Phase Policy

Date: 2026-04-24
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
Depends on / should follow:
- `work-cycle-docs/tickets/done/talos-execution-outcome-centralization.md`

## Why This Ticket Exists

The architecture review identified the biggest remaining runtime weakness
clearly:

Talos still has no explicit runtime phase model.

That means the model is still too trusted to blur:
- inspect
- plan
- apply
- verify

This is the underlying reason many bounded-task failures still feel chaotic.

## Problem

Talos currently has strong local guards:
- approval gate
- mutation-intent guard
- scope guard
- sandbox
- truthfulness overrides and summaries

But it still lacks an explicit answer to:

- what phase is the runtime in right now?
- which tools are legal in this phase?
- when must the runtime stop mutating and move to verification?

Without that, policy is still reactive rather than structured.

## Goal

Add a minimal explicit execution-phase policy that the runtime can enforce
without turning Talos into a planner-heavy framework.

## Important Constraint

This ticket is not permission to introduce:
- multi-step task decomposition
- heavyweight `Task`/`Step` orchestration
- verbose phase theater in the CLI

The goal is narrow runtime control, not a new product persona.

## Desired End State

Talos should have a small explicit phase model, likely along these lines:

- `INSPECT`
- `APPLY`
- `VERIFY`
- optional `RESPOND`

`PLAN` may be omitted initially if it does not meaningfully change runtime
policy yet.

The key point is:
- write/edit tools must not execute during inspect
- write/edit tools must not execute during verify
- apply still respects approval
- verify is a real runtime state, not just a prose suggestion

## Scope

### In scope

- a minimal phase enum/state model
- a small policy map for which tool categories are allowed in which phase
- phase-aware enforcement in the runtime
- minimal transition rules for common turns

### Out of scope

- planner/decomposer runtime
- user-visible phase UX by default
- broad prompt tuning project
- changing the tool surface

## Proposed Direction

### 1. Start minimal

Prefer a narrow policy such as:

- `INSPECT` -> read/search/retrieve only
- `APPLY` -> mutating tools allowed, still approval-gated
- `VERIFY` -> read/search/verification only

### 2. Derive phase from current turn intent and loop progress

Do not build a separate planning subsystem first.

Intended insertion points are already clear from the architecture docs and
current runtime seams:
- `AssistantTurnExecutor` derives or initializes the starting phase for the turn
- `ToolCallLoop` enforces transitions and phase-aware loop behavior
- `TurnProcessor` hard-gates tool execution using the current phase policy

### 3. Keep tool metadata simple

Avoid bloating `ToolDescriptor` immediately.
Use a sidecar runtime classification if needed.

### 4. Default verify direction for V1

For the bounded web/file workspace tasks Talos handles today, the intended
direction is automatic verify-after-apply once the verifier exists.

The exact rollout can still start narrow, but this ticket should be designed so
that `APPLY -> VERIFY` is the normal successful mutation path rather than an
optional afterthought.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/tools/ToolRegistry.java`
- maybe a new runtime policy class

## Open Design Questions

1. Is `PLAN` useful enough initially to justify a real phase, or should it stay
   implicit until later?
2. What is the narrowest useful state carrier for phase in V1: loop state,
   turn-scoped runtime state, or the emerging execution outcome/contract layer?

## Test / Verification Plan

### Core regressions

- inspect-first prompt cannot execute `edit_file` / `write_file`
- explicit mutation turn can enter apply and still reach approval
- verify phase blocks further mutation attempts

### Scenario coverage

- inspect before mutate
- apply then verify
- denied mutation path remains unchanged

## Acceptance Criteria

- Talos has a real enforced runtime phase policy, not just prompt guidance
- mutating tools are blocked outside apply
- current approval semantics remain intact
- the runtime becomes more predictable for bounded workspace tasks

## Completion Evidence

- Added `ExecutionPhase`, `ExecutionPhaseState`, and `PhasePolicy`.
- `AssistantTurnExecutor` initializes normal turns as `INSPECT` or `APPLY`
  from the latest user request and moves successful mutation turns toward
  `VERIFY`.
- `TurnProcessor` blocks mutating tools outside `APPLY` before approval or
  execution.
- Added unit coverage for phase policy, turn-processor enforcement, and
  executor phase initialization.
- Added JSON scenarios for forced `INSPECT` and `VERIFY` mutation blocking.
- Installed Talos was rebuilt and manually verified in
  `local/playground/horror-synth-site`; approval denial preserved files and
  stopped without retrying.

Remaining work belongs to the next ticket:
- static task verification
- richer apply-to-verify checks after successful mutation
- broader failure/reset policy
