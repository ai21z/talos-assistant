# [done] Ticket: Stream Filter Must Match Tool Parser Alias Semantics
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/29-v1-scenario-pack.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
Related tickets:
- `work-cycle-docs/tickets/talos-streaming-bare-tool-json-display-hygiene.md`
- `work-cycle-docs/tickets/talos-streaming-protocol-fence-and-pretool-prose-display.md`
- `work-cycle-docs/tickets/talos-raw-toolcall-json-final-answer.md`

## Why This Ticket Exists

Two completed streaming display tickets cleaned up important protocol leakage,
but installed verification on 2026-04-26 exposed a remaining parser/filter
parity bug.

The model emitted code-fenced JSON tool calls using noncanonical aliases such
as:

```json
{
  "name": "write_file",
  "arguments": { ... }
}
```

These appeared in the terminal stream before the tool loop outcome.

## Problem

`ToolCallParser` and `ToolRegistry` intentionally accept aliases:

- name-key aliases: `name`, `function`, `tool_name`, `tool`
- tool-name aliases: `write_file`, `edit_file`, etc.

But `ToolCallStreamFilter` still uses a narrower code-fence signature:

```java
"\"name\"\\s*:\\s*\"talos\\."
```

That suppresses only fenced JSON with canonical `"name": "talos.*"`.

It misses:

- `"name": "write_file"`
- `"function": "talos.write_file"`
- `"tool_name": "talos.edit_file"`
- canonicalizable aliases accepted by `ToolRegistry`

This violates the invariant that anything Talos will parse/execute as tool
protocol should not be streamed to the user as answer prose.

## Goal

Make stream-display tool-protocol detection use the same accepted identity
semantics as the parser/registry path, or a shared conservative helper that
cannot be narrower than the parser.

## Scope

### In scope

- Fix code-fenced JSON tool-call suppression for parser-supported name aliases.
- Fix code-fenced JSON tool-call suppression for registry-supported bare tool
  aliases such as `write_file`.
- Preserve display of ordinary non-tool JSON examples.
- Add regression tests using exact transcript shapes.

### Out of scope

- Changing tool execution behavior.
- Changing approval/phase policy.
- Broad stream rendering redesign.
- Hiding all JSON.

## Proposed Work

1. Replace the narrow `TOOL_CALL_JSON` regex with parser-aligned detection.

   Prefer one of:

   - expose/use `ToolCallParser.looksLikeStandaloneToolJson(...)` if access can
     stay package-local
   - add a small shared detector that accepts parser aliases and known
     canonicalizable tool names
   - use Jackson to inspect the fenced object and classify only Talos tool-call
     protocol

2. Include registry alias awareness.

   A fenced payload with `"name": "write_file"` is executable after alias
   rescue. It should be suppressed from live stream.

3. Pin non-tool JSON behavior.

   JSON examples such as config snippets must still display.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/tools/ToolRegistry.java` if a small alias helper is
  needed
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallStreamFilterTest"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest"
```

Required cases:

- suppress fenced JSON with `"name": "write_file"`
- suppress fenced JSON with `"function": "talos.write_file"`
- suppress fenced JSON with `"tool_name": "talos.edit_file"`
- suppress fenced adjacent tool calls
- preserve fenced non-tool JSON
- preserve ordinary code fences

Installed verification:

- Re-run the BMI/build prompt in `local/playground/horror-synth-site`.
- Confirm no visible fenced tool-call JSON appears in
  `local/manual-testing/test-output`.

## Acceptance Criteria

- Stream filter detection is not narrower than parser/registry executable
  protocol detection.
- Tool protocol no longer appears in the live terminal stream for alias shapes.
- Non-tool JSON remains visible.
- Final-answer raw JSON safety remains unchanged.
