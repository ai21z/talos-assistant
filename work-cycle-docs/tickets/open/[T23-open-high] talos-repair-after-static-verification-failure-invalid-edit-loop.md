# [T23-open-high] Ticket: Repair After Static Verification Failure Must Avoid Invalid Edit Loops
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T12-done-high] talos-pre-approval-mutating-required-args.md
- work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md
- work-cycle-docs/tickets/done/[T21-done-high] talos-post-denial-retry-must-reissue-action.md

## Why This Ticket Exists

T16 gives Talos a useful static verifier for web tasks. Manual testing showed the next failure mode: after static verification tells Talos exactly what is missing, the repair turn can enter an invalid `edit_file` loop and stop without fixing anything.

The guardrails are working, but task completion still fails because the assistant does not recover to a safer write strategy.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review/bmi-empty-c-repair-transcript.txt`

Prompt after partial BMI creation:

```text
Fix the remaining static verification problems now. Link scripts.js from index.html and add a calculate button that calls the BMI logic. Use file tools and do not just show code.
```

Observed:

- Trace: `contract: FILE_CREATE mutationAllowed=true verificationRequired=true`.
- Mutating tools were exposed.
- Talos attempted `edit_file` with invalid or placeholder arguments:
  - empty `old_string`
  - placeholder `new_string` such as `<head>` and `<form>`
  - repeated failed edit against `index.html`
- Failure policy stopped the loop.
- No file changed.

This is better than approving invalid edits, but it is still poor operator behavior. Once the model cannot produce a valid exact-string edit after reading the file, Talos should either:

- force a bounded re-read + exact replacement retry, or
- nudge the model to use `write_file` for the whole target file, or
- stop with a deterministic blocked outcome that explains the next safe action.

## Goal

Repair turns after static verification failure should not churn through invalid `edit_file` calls. Talos should recover to a safer strategy or stop with a more actionable, deterministic reason.

## Scope

In scope:
- Detect repeated invalid edit attempts for the same path in a repair turn.
- Prefer a bounded retry instruction that says to re-read the file and either use exact `old_string` or overwrite the target file with `write_file`.
- Keep pre-approval validation strict.
- Add deterministic tests for the invalid-edit repair loop.

Out of scope:
- Browser execution.
- New shell/test-runner tools.
- Broad planning architecture.
- Weakening placeholder guards.

## Proposed Work

- Extend failure-policy or reprompt-stage handling for repeated invalid `edit_file` arguments after a repair request.
- Ensure the model is given a precise recovery instruction once, not an unlimited retry.
- Consider a deterministic post-failure answer if no valid tool call is produced.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopP0Test.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit test with scripted model:
  - initial static verification failure in history,
  - repair prompt,
  - model emits invalid edit args,
  - Talos sends bounded recovery instruction or returns deterministic blocked outcome.
- E2E scenario for partial web app repair.
- Manual Talos test in BMI workspace:
  - create partial BMI app,
  - ask to fix remaining verifier problems,
  - confirm Talos either repairs or gives a truthful actionable block.

## Acceptance Criteria

- Invalid edit args still do not reach approval.
- Repeated invalid edit attempts do not produce vague prose or raw tool dumps.
- Talos does not claim completion when no file changed.
- Repair turn either applies a valid fix or reports a deterministic blocked repair outcome.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `bmi-empty-c-repair-transcript.txt` shows a mutation-allowed repair turn stopped after invalid `edit_file` calls for `index.html`, despite static verifier giving concrete missing items.

Additional non-technical phrasing evidence on 2026-04-28:

- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`
  - After the user said `I'm sorry, maybe I'm saying this wrong. I need this folder to become a BMI calculator page. You can change whatever files are needed. Please make it work.`
  - Talos edited `index.html`, then repeated an edit whose `old_string` no longer matched.
  - Final result was partial:
    - duplicate `id="weight"` inputs,
    - duplicate `id="height"` inputs,
    - duplicate `id="result"` elements,
    - no calculate button,
    - no `scripts.js`,
    - no JavaScript link.
  - Trace correctly showed `FILE_EDIT mutationAllowed=true`, but repair strategy did not converge.

This strengthens the acceptance criterion: repair recovery must account for successful-but-incomplete edits as well as failed invalid edit loops. After an edit changes the anchor text, Talos should re-read before attempting another edit or switch to `write_file` for the target file.
