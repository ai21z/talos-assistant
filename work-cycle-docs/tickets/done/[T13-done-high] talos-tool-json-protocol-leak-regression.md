# [done] Ticket: Tool JSON Protocol Must Not Leak Or Silently Fail
Date: 2026-04-27
Priority: high
Status: done
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

## Current Code Read

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallParseStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Planned Tests

- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest"`
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallStreamFilterTest"`
- Focused JSON-backed e2e scenario for fenced `write_file` JSON with JavaScript template-literal content
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Manual installed Talos check in `local/manual-workspaces/T13/`

## Acceptance Criteria

- Fenced JSON with `name` and `arguments` is parsed and executed when valid.
- Structurally invalid tool-shaped JSON is hidden from visible prose and
  reported as a protocol failure.
- No raw `talos.*` tool-call JSON appears in the final answer.
- Debug trace explains whether execution or rejection happened.

## Implementation Summary

- Fixed fenced tool-call JSON parsing so valid `name` + `arguments` blocks are
  still detected when tool argument strings contain JavaScript backticks.
- Added parser coverage for parsing and stripping a fenced `talos.write_file`
  call whose `content` includes a template literal.
- Added stream-filter coverage to keep the same fenced protocol text out of
  streamed visible output.
- Added a deterministic JSON-backed e2e scenario proving the backtick-bearing
  `write_file` call executes and does not leak protocol JSON into the final
  answer.

## Tests Run

- RED before implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest.parseCodeFencedWriteFileWithBackticksInContent" --tests "dev.talos.runtime.ToolCallParserTest.stripToolCallsRemovesCodeFencedWriteFileWithBackticksInContent"` -> FAIL, parser returned zero calls and stripping left protocol text visible.
- GREEN after implementation:
  `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest.parseCodeFencedWriteFileWithBackticksInContent" --tests "dev.talos.runtime.ToolCallParserTest.stripToolCallsRemovesCodeFencedWriteFileWithBackticksInContent"` -> PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest" --tests "dev.talos.runtime.ToolCallStreamFilterTest"` -> PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.fencedWriteJsonWithBackticksExecutes"` -> PASS.
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"` -> PASS.
- `./gradlew.bat e2eTest` -> PASS.
- `./gradlew.bat check` -> PASS.

## Work-Test-Cycle Loop Used

Inner dev loop. This ticket is runtime/protocol-sensitive, so focused unit
tests, focused e2e, full e2e, hard gate `check`, and installed manual Talos
verification were run. Candidate loop was not run because this is one ticket in
the T11-T18 batch, not a declared candidate release.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`
`./gradlew.bat clean installDist --no-daemon`
`pwsh .\tools\install-windows.ps1 -Force -Quiet`
Then piped `/session clear`, `/debug trace`, the prompt, approval `y`, and
`/q` into the installed Talos CLI.

Workspace:
`local/manual-workspaces/T13/`

Model:
`qwen2.5-coder:14b`

Prompt:
```text
Create scripts.js in this workspace with exactly this JavaScript line: const message = `Your BMI is ${bmi.toFixed(2)}`; Use the file tool and do not just show code.
```

Approval choice:
`y`

Observed tools:
`talos.write_file`

Files changed:
`local/manual-workspaces/T13/scripts.js`

Output file:
`local/manual-testing/T13-output.txt`

Pass/fail:
PASS

Notes:
The installed CLI requested write approval, created `scripts.js` with the
backtick-containing template literal, and did not print a fenced JSON protocol
block as normal answer text. The transcript contains `talos.write_file` only in
approval/trace diagnostics, which is expected.

## Known Follow-Ups

- This ticket fixes a concrete valid-JSON parser gap. Malformed-but-tool-shaped
  JSON remains covered by the broader protocol-debris invariant and should stay
  under regression coverage as additional transcript shapes are found.
