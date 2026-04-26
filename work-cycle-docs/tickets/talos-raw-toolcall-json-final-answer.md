# [done] Ticket: Raw Tool-Call JSON Must Not Escape As Final Answer

Date: 2026-04-24
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
Related runtime-history tickets:
- `work-cycle-docs/tickets/talos-scenario-harness-v1.md`
- `work-cycle-docs/tickets/talos-execution-outcome-centralization.md`

## Why This Ticket Exists

The latest packaged installed-CLI review exposed a live runtime failure that is
separate from execution-outcome centralization.

In a real `auto` session against `local/playground/horror-synth-site`, Talos:

1. entered the tool loop for a read-only audit prompt
2. executed `talos.list_dir`
3. received a follow-up assistant response containing raw JSON for a
   `talos.grep` call
4. exited the turn with that raw tool-call JSON as the final user-visible answer

This is not an acceptable final state for a local-first assistant.

Even if the model is weak, Talos must not let unfinished tool-call JSON escape
as the final answer when the runtime has already entered the tool loop.

## Problem

Talos still has a continuation failure shape where:

- tool-loop entry is detected correctly
- at least one tool is executed
- the follow-up model response is still effectively another tool-call stub /
  raw tool-call JSON
- the runtime accepts that text as the final answer instead of:
  - parsing and continuing,
  - retrying once,
  - or replacing it with a truthful fallback

This creates a user-facing transcript failure that looks like Talos stopped
halfway through execution.

## Goal

Once Talos has entered the tool loop, raw tool-call JSON must not survive as
the final answer.

## In Scope

- reproduce and pin the exact packaged-run failure shape
- determine whether the bug is in:
  - tool-call parsing continuation,
  - loop termination,
  - final-answer acceptance,
  - or the streaming/non-streaming bridge
- add a runtime fix so raw tool-call JSON is not accepted as the final answer
  after the loop has already started

## Out Of Scope

- general model quality improvement
- phase-policy work
- verifier work
- prompt tuning as the primary fix

## Desired Runtime Behavior

After any tool-loop turn:

- if the follow-up assistant text is still parseable as tool calls,
  the loop should continue
- if the text is malformed but obviously still an unfinished tool-call payload,
  Talos should not surface it as the final answer unchanged
- the user should either receive:
  - a completed tool-backed answer
  - or a truthful runtime fallback, not raw tool JSON

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/*`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- existing executor-path harness scenarios

## Required Tests

1. packaged-failure-shape regression:
   - read-only workspace audit prompt
   - model emits `list_dir`
   - follow-up emits raw JSON for another tool call
   - expected: raw tool-call JSON is not the final answer

2. loop-continuation regression:
   - follow-up tool-call JSON after first successful tool
   - expected: parser/loop continues correctly

3. malformed-continuation fallback:
   - follow-up looks like unfinished tool-call payload but cannot be safely run
   - expected: truthful fallback instead of raw JSON leak

4. stability checks:
   - existing tool-loop regressions still pass
   - execution-outcome centralization remains intact

## Acceptance Criteria

- raw tool-call JSON does not escape as the final answer after tool-loop entry
- the packaged horror-synth-site regression shape is covered
- the fix is runtime-centered and does not depend on prompt tuning
