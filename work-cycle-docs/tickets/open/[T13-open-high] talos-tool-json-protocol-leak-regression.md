# [open] Ticket: Tool JSON Protocol Must Not Leak Or Silently Fail
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-raw-toolcall-json-final-answer.md`
- `work-cycle-docs/tickets/done/talos-multi-adjacent-raw-json-toolcalls.md`
- `work-cycle-docs/tickets/done/talos-stream-filter-tool-alias-parity.md`
- `work-cycle-docs/tickets/done/talos-streaming-bare-tool-json-display-hygiene.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

In the manual transcript, Talos printed a fenced JSON tool call for
`talos.write_file` as visible answer text instead of executing it or rejecting
it:

```json
{
  "name": "talos.write_file",
  "arguments": {
    "path": "scripts.js",
    "content": "..."
  }
}
```

The turn trace showed mutation allowed and tools exposed, but the protocol text
became user-visible output.

## Problem

This may be caused by parser detection failure, stream display leakage,
native-vs-text fallback mismatch, malformed JSON handling, or final-answer
sanitization. The ticket must not assume a single root cause before tests pin
down the failure.

The invariant is simpler:

```text
Recognizable tool protocol text must end in exactly one of three states:
1. executed,
2. structurally rejected with a clear reason,
3. hidden as protocol debris.

It must never silently leak as normal prose.
```

## Goal

Make tool-call JSON handling deterministic and user-safe across streaming,
non-streaming, native-tool, and text-fallback paths.

## Scope

### In scope

- Reproduce the transcript-shaped fenced JSON leak.
- Check parser detection vs extraction symmetry.
- Check stream filter and final-answer stripping behavior.
- Ensure malformed-but-tool-shaped JSON receives a truthful protocol fallback
  instead of being printed as normal answer text.
- Add regression coverage for `name` + `arguments` fenced JSON.

### Out of scope

- New tool schema.
- Changing the model provider.
- Relying on prompt-only fixes.

## Proposed Work

1. Add parser/unit coverage for the exact leaked JSON shape.
2. Add stream-filter coverage for the same shape.
3. Add an executor or E2E scenario where the model emits that JSON and Talos
   must either execute it or report a structured protocol failure.
4. Ensure final user-visible answers do not contain raw `talos.write_file`
   protocol blocks.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused parser and stream-filter tests.
- Deterministic E2E scenario with a leaked fenced JSON tool call.
- Manual retest with `/debug trace` after install.

## Acceptance Criteria

- Fenced JSON with `name` and `arguments` is parsed and executed when valid.
- Structurally invalid tool-shaped JSON is hidden from visible prose and
  reported as a protocol failure.
- No raw `talos.*` tool-call JSON appears in the final answer.
- Debug trace explains whether execution or rejection happened.
