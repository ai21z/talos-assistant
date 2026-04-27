# [done] Ticket: Malformed JSON Array Display Hygiene
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-raw-toolcall-json-final-answer.md`
- `work-cycle-docs/tickets/done/talos-streaming-bare-tool-json-display-hygiene.md`

## Why This Ticket Exists

Installed Talos verification for the `repair` mutation-intent ticket produced a
malformed protocol-looking answer:

```text
[
    ,

]
```

The next piped input (`n`) was then treated as a normal user message because no
approval prompt had appeared.

This is not the same as bare Talos tool-call JSON leakage. It is malformed JSON
array debris, likely from a failed native-tool response attempt.

## Problem

Talos currently suppresses several protocol shapes:

- XML tool blocks
- fenced JSON tool calls
- bare standalone Talos tool-call JSON
- raw tool-call JSON as final answer after tool-loop entry

But a malformed array-shaped protocol fragment can still appear as ordinary
assistant output.

## Goal

Prevent obvious malformed tool/protocol JSON array debris from being shown as a
normal answer, while preserving ordinary user-facing JSON examples.

## Scope

### In scope

- Detect small malformed JSON-array protocol debris such as `[ , ]`.
- Replace it with a concise truthful fallback, for example:
  `The model produced an invalid tool-call payload and no action was taken.`
- Add tests for final-answer shaping and/or stream display.
- Preserve non-tool JSON examples.

### Out of scope

- Broad JSON linting of all assistant answers.
- Changing tool execution semantics.
- Allowing malformed calls to reach approval.

## Proposed Work

1. Decide whether this belongs in `ToolCallStreamFilter`,
   `ToolCallParser.containsToolCalls(...)`, or final answer shaping.
2. Add a narrow detector for empty/malformed array protocol debris.
3. Add deterministic tests around the observed shape.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallStreamFilterTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
```

Manual:

- Re-run a repair prompt that previously produced `[ , ]`.
- Confirm the malformed JSON array is not displayed as a normal answer.

## Acceptance Criteria

- The observed `[ , ]` shape no longer appears as normal assistant output.
- Talos clearly says no tool/action occurred.
- Ordinary JSON examples still display.
