# [T27-open-high] Ticket: Malformed Tool-Call JSON-Like Output Must Not Leak Or Stall
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T13-done-high] talos-tool-json-protocol-leak-regression.md

## Why This Ticket Exists

Manual testing found a protocol failure distinct from T24. In a mutation-allowed turn, the model emitted a JSON-like `talos.edit_file` call using single-quoted string values. Talos displayed the protocol text to the user instead of executing it, rejecting it as malformed protocol, or reprompting for valid JSON/native tool use.

This leaves the user with apparent tool syntax, no approval prompt, and no file changes.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review-2/nondev-button-broken-transcript.txt`

Prompt:

```text
My BMI page is almost there, but when I press the button nothing happens. Please keep the look the same and just make the button work.
```

Observed:

- Trace: `contract: FILE_EDIT mutationAllowed=true verificationRequired=true`.
- Talos read the files.
- Final answer displayed:

```text
{
  "name": "talos.edit_file",
  "arguments": {
    "path": "scripts.js",
    "old_string": 'document.querySelector("#wrongButton").addEventListener("click", () => {',
    "new_string": 'document.querySelector("button").addEventListener("click", () => {'
  }
}
```

- No approval prompt appeared.
- `scripts.js` was unchanged.
- Follow-ups produced more JSON-like `edit_file` blocks and `[Tool-call continuation could not be completed...]`.

This is not merely an invalid argument issue. The apparent tool call never reached the tool execution/approval path in a structured way.

## Goal

Tool-call-looking protocol text must end in one of these states:

- valid tool call executed through approval/tool loop,
- malformed protocol rejected with deterministic explanation,
- bounded reprompt asking the model for valid tool JSON/native tool call.

It must not leak as ordinary assistant prose.

## Scope

In scope:
- Detect JSON-like tool protocol blocks that are not valid JSON due to single quotes or similar near-miss syntax.
- Sanitize or replace such blocks in final visible answers.
- Add regression tests for malformed JSON-like tool calls in mutation-allowed turns.

Out of scope:
- Supporting arbitrary JavaScript object literal parsing as a new tool protocol.
- Weakening approval gates.
- Browser/runtime testing of web pages.

## Proposed Work

- Extend `ToolCallParser.containsToolCalls(...)` or add a sibling malformed-protocol detector for JSON-like tool objects with `name` and `arguments`.
- In mutation-allowed turns, if malformed protocol is detected and no tool executed, return a deterministic blocked/protocol error or reprompt once.
- Ensure final answer does not include the raw protocol object.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Parser/unit tests:
  - valid JSON still parses,
  - single-quoted JSON-like tool object is detected as malformed protocol,
  - malformed protocol does not leak.
- Executor/e2e test:
  - mutation-allowed prompt,
  - model emits single-quoted JSON-like `edit_file`,
  - final answer reports malformed tool protocol or reprompts,
  - no raw JSON-like object appears.
- Manual Talos check with the reproduced `button does nothing` workspace.

## Acceptance Criteria

- Raw malformed tool-call object does not appear in final answer.
- Talos does not imply a file was edited when no tool executed.
- If a reprompt is used, it is bounded to one retry.
- Approval is still required before any mutation.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `nondev-button-broken-transcript.txt` shows a mutation-allowed turn displaying single-quoted `edit_file` protocol text with no approval and no mutation.
