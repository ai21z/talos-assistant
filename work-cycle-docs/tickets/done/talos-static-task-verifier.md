# [done] Ticket: Static Post-Apply Task Verifier

Date: 2026-04-24
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-plan.md`
- `docs/architecture/talos-harness-source-of-truth.md`
Depends on / should follow:
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-execution-outcome-centralization.md`
Related prior ticket:
- `work-cycle-docs/tickets/done/talos-post-edit-truthfulness-and-analysis.md`

## Why This Ticket Exists

Talos already has useful verification pieces:
- per-file verification
- placeholder-content rejection
- selector mismatch checks
- mutation truth layers

But the architecture review confirmed the central remaining trust gap:

Talos still does not have task-level verification as a first-class runtime
step.

A file can be changed successfully and still leave the user's actual task
unfinished.

## Problem

Today Talos can often answer as though a task is complete when the runtime has
only proved a much smaller fact, for example:
- a file was written
- an edit matched
- some local content looks syntactically plausible

That is not the same as proving:
- the requested file actually changed
- only the intended target changed
- cross-file references still align
- the requested local web/file task is now coherent

## Goal

Add a narrow static verifier that runs after successful apply work and produces
a structured verification result before Talos claims completion.

## Scope Clarification

The larger vision docs sometimes describe verifier behavior in terms of a later
`TaskContract`-style abstraction.

That abstraction is intentionally not part of the immediate V1 ticket set.

So this ticket must stay honest about what V1 verification can do without a
full task contract:
- static workspace consistency checks
- expected/forbidden path checks where the runtime already knows them
- post-apply structural sanity checks

It must not pretend to fully understand all user intent yet.

## Important Constraint

Do not introduce shell execution, browser automation, or test-runner
verification in this ticket.

The source-of-truth docs are clear: Talos should stay bounded and local-first.
Static verification gives the highest trust gain for the least architectural
risk right now.

## Desired End State

For relevant local workspace tasks, Talos should be able to verify facts such as:

- expected target file changed
- forbidden target file did not change
- referenced CSS/JS files exist
- JavaScript selectors exist in HTML when required
- no placeholder or empty overwrite survived
- no unexpected file was introduced

Talos should then distinguish:
- changed
- changed and verified
- changed but verification incomplete
- changed but verification failed

In V1 this should be interpreted as mostly intent-light verification:
- structural consistency
- observed target/path effects
- cross-file linkage and local coherence

Intent-aware semantic completion remains later work.

## Scope

### In scope

- static post-apply verification
- structured verification result
- integration with final answer/outcome shaping
- initial focus on local workspace file and small web-app tasks

### Out of scope

- shell/test commands
- browser runtime checks
- full semantic correctness guarantees
- large generalized workflow planning

## Proposed Direction

### 1. Add a dedicated verifier abstraction

Keep it narrow and runtime-centered.
Do not overload `ContentVerifier` into a giant everything-class.

### 2. Start with static cross-file checks

Especially for the web/file tasks Talos already handles:
- HTML/CSS/JS linkage
- missing selectors/elements
- expected mutation target changed
- forbidden/unexpected changes absent

### 3. Feed verifier output into the central execution outcome

The final answer should not claim verified completion without an actual
verification result.

## Likely Files / Areas

- new verifier class/package in runtime
- `AssistantTurnExecutor`
- `ToolCallLoop`
- existing local verification helpers
- possibly `ContentVerifier` for shared lower-level checks

## Open Design Questions

1. Should verification be automatic for every successful mutation, or only for
   known safe task shapes first?
2. How should verifier results be represented in the central outcome model?
3. Should the verifier consume only workspace state, or also actual tool
   outcomes and intended target information?

## Non-Goal Reminder

This ticket does not introduce:
- a planner
- a broad `TaskContract`
- browser/runtime execution verification
- shell/test-runner verification

## Test / Verification Plan

### Required

- successful file change but missing expected cross-file linkage -> verification fails
- expected target changed / forbidden target unchanged -> verification passes
- partial mutation turn -> verifier does not incorrectly bless the whole task

### Scenario coverage

- explicit HTML/CSS/JS repair with post-apply verification
- false completion regression no longer survives as “done”

## Acceptance Criteria

- Talos has a real static post-apply verifier for bounded workspace tasks
- completion claims distinguish verified from merely applied changes
- existing truthful denied/partial mutation behavior remains intact
- the verifier improves trust without requiring shell/browser expansion

## Completion Notes

Implemented a narrow static post-apply verifier slice under
`dev.talos.runtime.verification`.

Completed behavior:
- successful mutation turns now run structured static verification through the
  central `ExecutionOutcome` path
- final answers distinguish static verification passed, failed, incomplete, and
  not-run states
- mutated target paths must still exist, stay readable, and avoid obvious
  template-placeholder residue
- file-level write/edit verification warnings feed into task verification
- selector/linkage repair tasks check HTML/CSS/JS class and ID coherence without
  treating CSS hex colors as ID selectors
- partial mutation turns are not blessed as fully verified completion

Verification completed:
- focused verifier and execution outcome unit tests
- full unit test suite
- full e2e suite
- JSON scenario pack with static verifier pass/fail/partial cases
- installed Talos verification against a disposable horror-synth workspace copy
- candidate jar, check, quality summaries, and markdown reports

Qodana Community was attempted, but Docker Desktop was unavailable; generated
Qodana evidence is therefore stale-provenance evidence only.

Still out of scope:
- broad semantic task verification
- `TaskContract`
- shell/browser/test-runner verification
- live-stream raw tool JSON display hygiene, tracked separately as medium
  priority
