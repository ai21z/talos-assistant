# [done] Ticket: Adjacent Raw JSON Tool Calls Must Parse As Multiple Continuations

Date: 2026-04-25
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-plan.md`
- `docs/architecture/talos-harness-source-of-truth.md`
Related runtime-history tickets:
- `work-cycle-docs/tickets/done/talos-raw-toolcall-json-final-answer.md`
- `work-cycle-docs/tickets/done/talos-execution-outcome-centralization.md`

## Why This Ticket Exists

The raw tool-call JSON leak was fixed, and Talos now truthfully suppresses
unfinished continuation payloads instead of surfacing them as final answers.

But the installed-CLI review also showed a separate parser limitation:

- the model emitted two adjacent standalone raw JSON tool objects
- `ToolCallParseStage` reported only one parsed text tool call on that
  iteration
- Talos continued safely, but it did not parse and execute the full adjacent
  multi-call payload as intended

This is not the same bug as raw JSON leaking. It is a separate continuation
parsing weakness and should be tracked independently.

## Problem

Talos now recognizes a single standalone raw JSON tool payload, but it still
does not reliably parse multiple adjacent standalone raw JSON tool objects in
one assistant response.

That means the runtime can:

- execute the first adjacent raw JSON tool call
- miss the second one in the same text response
- fall back into extra loop turns or fallback behavior instead of handling the
  continuation cleanly in one iteration

## Goal

When the model emits multiple adjacent standalone raw JSON tool-call payloads in
one response, Talos should parse them all as tool calls in that iteration.

## In Scope

- extend the text-fallback parser so adjacent standalone raw JSON objects can be
  extracted as multiple tool calls
- preserve the current fix that prevents raw tool-call JSON from escaping as the
  final answer
- add deterministic regressions for adjacent raw JSON multi-call payloads

## Out Of Scope

- native tool-calling changes
- phase-policy work
- verifier work
- prompt tuning as the primary fix

## Desired Runtime Behavior

Given a follow-up assistant response like:

```json
{
  "name": "talos.read_file",
  "arguments": { "path": "script.js" }
}

{
  "name": "talos.read_file",
  "arguments": { "path": "style.css" }
}
```

Talos should:

- parse both tool calls
- execute both in the same loop iteration
- not require an extra recovery step just because the calls were emitted as
  adjacent raw JSON objects instead of fenced blocks or XML wrappers

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallParseStage.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`

## Required Tests

1. adjacent raw JSON multi-call parser regression:
   - two adjacent standalone raw JSON tool objects
   - expected: both parse

2. loop regression:
   - first tool executes
   - follow-up emits two adjacent standalone raw JSON tool calls
   - expected: both execute in the same subsequent iteration

3. stability regression:
   - single standalone raw JSON tool payload still works
   - malformed continuation fallback still works
   - raw tool-call JSON still does not escape as final answer

## Acceptance Criteria

- adjacent standalone raw JSON tool-call payloads are parsed as multiple calls
- the installed horror-synth-site failure shape no longer drops the second
  adjacent raw JSON continuation call
- the prior raw-final-answer fix remains intact
