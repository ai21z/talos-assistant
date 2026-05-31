# [done] Ticket: Streaming Bare Tool-Call JSON Display Hygiene

Date: 2026-04-25
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-plan.md`
- `docs/architecture/talos-harness-source-of-truth.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-raw-toolcall-json-final-answer.md`
- `work-cycle-docs/tickets/done/talos-multi-adjacent-raw-json-toolcalls.md`
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
Evidence:
- installed CLI transcript: `local/manual-testing/test-output`

## Why This Ticket Exists

The installed Talos verification for the minimal execution-phase policy showed
that raw bare JSON tool-call payloads can still appear in the live terminal
stream before the tool loop consumes them.

This is not the same bug as `talos-raw-toolcall-json-final-answer.md`.
That ticket fixed raw tool-call JSON escaping as the final answer after the
runtime had entered the tool loop.

The current issue is display hygiene:
- the final answer is clean
- the tool loop executes correctly
- but the live captured stream still shows protocol JSON such as:

```json
{
  "name": "talos.read_file",
  "arguments": {
    "path": "index.html"
  }
}
```

For a polished local workspace assistant, internal tool-call protocol should
not be printed to the user as ordinary answer text.

## Problem

`ToolCallStreamFilter` currently suppresses:
- deprecated XML tool-call blocks
- JSON code-fenced tool calls containing a `"name": "talos."` signature

It does not suppress bare standalone JSON tool calls.

The current Ollama/qwen streaming path frequently emits text-form tool calls as
bare JSON objects rather than fenced JSON. `ToolCallParser` can parse these
objects and `ToolCallLoop` can execute them, but the stream filter prints them
to the terminal before the loop gets control.

This creates a transcript that is functionally correct but visibly unpolished:
- users see internal protocol objects
- the terminal output looks like unfinished assistant prose
- manual review has to distinguish tool protocol leakage from final answer
  truthfulness

## Goal

Suppress bare standalone Talos tool-call JSON from the user-visible streaming
output while preserving:
- normal prose
- non-tool JSON examples
- tool execution behavior
- final-answer sanitization behavior

The runtime should still retain the full raw response text internally so
`ToolCallLoop` can parse and execute the tool calls.

## Scope

### In scope

- extend stream-display filtering for bare standalone Talos tool-call JSON
- handle chunk boundaries for streamed JSON objects
- handle adjacent bare JSON tool calls if they are streamed together
- keep final-answer JSON stripping behavior intact
- add deterministic unit tests for the stream filter
- optionally add an executor/installed-transcript-style regression if the
  existing seams make that practical without live Ollama

### Out of scope

- changing tool-call parser semantics unless a small shared helper is needed
- changing final-answer outcome shaping
- changing model prompts as the primary fix
- hiding debug logs
- changing approval, phase, verifier, or tool execution policy

## Technical Analysis

The likely implementation area is:

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`

Current wiring:

- `TalosBootstrap` wraps the terminal stream sink in `ToolCallStreamFilter`.
- `AssistantTurnExecutor` calls `ctx.llm().chatStreamFull(messages,
  ctx.streamSink())`.
- `chatStreamFull` returns the full raw model response for parser/loop use.
- The filter only controls display; it must not mutate the raw text returned to
  the tool loop.

Current gap:

- `ToolCallStreamFilter` has states for:
  - `PASSTHROUGH`
  - `SUPPRESSING_XML`
  - `BUFFERING_FENCE`
  - `SUPPRESSING_FENCE`
- Bare JSON starts with `{`, so the filter remains in `PASSTHROUGH`.
- `findSafeEmitEnd(...)` only protects partial XML tags and code fences at
  chunk boundaries. It does not hold a possible JSON object long enough to
  decide whether it is a Talos tool call.

Suggested implementation direction:

1. Add a bounded bare-JSON buffering state.

   When passthrough sees a `{` that could begin a standalone object, buffer
   until the matching top-level `}` is available or the candidate clearly stops
   being a tool-call object.

2. Classify buffered JSON conservatively.

   Suppress only if the complete object looks like a Talos tool call:
   - top-level `"name"` or `"tool_name"` starts with `talos.`
   - and it contains `"arguments"`, `"parameters"`, or `"params"` as an object
     field, or matches the existing parser-supported shape

   Prefer using Jackson if available in main runtime dependencies; otherwise use
   a narrow structural scanner. Avoid broad regex deletion of arbitrary JSON.

3. Preserve non-tool JSON.

   If the object is not a Talos tool-call object, emit the buffered object
   exactly as normal text.

4. Preserve prose around tool calls.

   Text before and after a bare tool-call object should still stream normally.
   For adjacent tool-call objects, suppress each protocol object and emit only
   any real prose between/after them.

5. Flush behavior must be deliberate.

   On stream completion:
   - incomplete recognizable tool-call JSON can be discarded as protocol debris
   - incomplete ordinary JSON should be emitted as normal text
   - the tests should pin whichever behavior is selected

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- optionally `src/main/java/dev/talos/runtime/ToolCallParser.java` if a small
  shared detector avoids duplicate JSON-shape logic
- optionally `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
  for an executor-level transcript-shape regression

## Test / Verification Plan

### Unit tests

- bare standalone JSON tool call is suppressed
- chunked bare JSON tool call is suppressed
- adjacent bare JSON tool calls are suppressed
- prose before and after bare JSON tool calls is preserved
- non-tool JSON passes through unchanged
- JSON code-fence and XML suppression regressions still pass
- incomplete bare tool-call JSON on flush does not leak obvious protocol text

### Manual verification

After implementation, rebuild/install Talos and rerun the manual prompt flow in:

```text
local/playground/horror-synth-site
```

Review `local/manual-testing/test-output` for:
- no bare `{"name":"talos...` / multiline `"name": "talos..."` protocol
  objects in user-visible stream output
- final answer still reports selector mismatch truthfully
- tool loop still executes tools
- approval denial still prevents writes
- session saves cleanly

## Acceptance Criteria

- bare standalone Talos tool-call JSON no longer appears in the user-visible
  streaming transcript
- final answers remain free of raw tool-call JSON
- tool execution behavior is unchanged
- code-fenced JSON tool-call suppression still works
- non-tool JSON examples still display correctly
- installed CLI manual transcript confirms the display fix

## Completion Notes

Implemented a bounded bare-JSON buffering state in `ToolCallStreamFilter`.

Completed behavior:
- bare standalone Talos tool-call JSON is suppressed from user-visible streaming
  output
- chunked bare JSON tool calls are suppressed
- adjacent bare JSON tool calls are suppressed
- prose before/after tool-call JSON is preserved
- non-tool JSON examples still pass through
- CSS braces are not mistaken for JSON tool-call starts
- incomplete bare Talos tool-call JSON is discarded on flush instead of leaking
  protocol debris
- the raw model response remains available to `ToolCallLoop`, so tool execution
  behavior is unchanged

Verification completed:
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallStreamFilterTest"`
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest"`
- `./gradlew.bat test --tests "dev.talos.runtime.NativeToolPipelineTest"`
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"`
- `./gradlew.bat test`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- installed Talos manual verification against `local/playground/horror-synth-site`

Manual transcript result:
- no visible bare `talos.*` JSON protocol object appeared in the stream
- read-only inspection stayed read-only
- selector mismatch grounding remained truthful
- approval denial prevented the edit and stopped cleanly
- tracked playground files remained unchanged
- session saved cleanly

Residual non-blocking observation:
- the installed transcript still showed an empty/malformed JSON code fence with
  `"name": null`; that is not a bare Talos tool-call JSON leak and should be
  tracked separately if stream display polish is tightened further.
