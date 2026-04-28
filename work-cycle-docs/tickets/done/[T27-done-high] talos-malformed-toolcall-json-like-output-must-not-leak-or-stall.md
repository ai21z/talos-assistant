# [T27-done-high] Ticket: Malformed Tool-Call JSON-Like Output Must Not Leak Or Stall
Date: 2026-04-28
Priority: high
Status: done
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

## Current Code Read

Inspected before implementation:

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallParseStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- existing JSON scenario pack tests and scenario resources

Current diagnosis:

- Valid text tool calls are routed through `ToolCallParser.containsToolCalls(...)` and `ToolCallLoop`.
- Existing malformed-protocol handling is narrow and only covers comma-only array debris.
- A JSON-like object with a recognized Talos tool name and `arguments`, but invalid string quoting inside argument values, can fall through as no tool/no structured protocol error and leak as assistant prose.

Planned tests:

- Parser coverage for detecting and stripping malformed JSON-like Talos tool protocol.
- Executor coverage proving malformed protocol in a mutation-allowed turn becomes a truthful no-action protocol replacement and does not leak raw object text.
- E2E JSON scenario matching the single-quoted `talos.edit_file` transcript shape.

## Implementation Summary

- Added a narrow malformed Talos tool-protocol detector in `ToolCallParser` for brace-balanced JSON-like objects with a recognized Talos tool-name field that cannot be parsed as executable JSON.
- Extended tool-call stripping so malformed protocol objects are removed from user-visible output instead of leaking as prose.
- Routed malformed protocol through the existing deterministic no-action replacement in `AssistantTurnExecutor` and `ExecutionOutcome`.
- Added focused parser, executor, and JSON e2e coverage for the reproduced single-quoted `talos.edit_file` shape.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not update `CHANGELOG.md`.

## Tests Run

Initial red check:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest" --no-daemon
```

Result: FAIL before implementation because `ToolCallParser.looksLikeMalformedToolProtocol(String)` did not exist.

Focused parser tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest" --no-daemon
```

Result: PASS.

Focused executor tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Result: PASS.

Focused e2e scenario:

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.malformedToolcallJsonLikeOutputDoesNotLeakOrMutate" --no-daemon
```

Result: PASS.

Full deterministic e2e:

```powershell
./gradlew.bat e2eTest --no-daemon
```

Result: PASS.

Hard gate:

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet

cd local\manual-workspaces\T27
talos
```

Workspace:

```text
local/manual-workspaces/T27
```

Model:

```text
qwen2.5-coder:14b
```

Prompt:

```text
My BMI page is almost there, but when I press the button nothing happens. Please keep the look the same and just make the button work.
```

Approval choice:

```text
No approval appeared for the saved malformed/continuation transcript. A separate tool-directed run produced a normal edit approval, which was denied by the scripted input.
```

Observed tools:

```text
Saved transcript: talos.grep, talos.list_dir, talos.read_file.
Tool-directed transcript: talos.read_file, talos.edit_file.
```

Files changed:

```text
None.
```

Output file:

```text
local/manual-testing/T27-output.txt
local/manual-testing/T27-output-invalid-protocol.txt
```

Pass/fail:

```text
PASS.
```

Notes:

- The clean saved transcript did not leak raw malformed `talos.edit_file` JSON-like protocol text and did not mutate files.
- A tool-directed run followed the valid approval-gated edit path; approval denial left files unchanged and produced truthful no-change wording.
- The deterministic unit and e2e tests exercise the exact malformed single-quoted protocol object from the ticket.

## Known Follow-Ups

- Live qwen can still fail to complete the repair by ending in the existing bounded continuation fallback. That is a repair-loop/task-completion issue, not a T27 protocol-leak blocker.
- T24 remains the narrower blocked-tool/read-only-denial protocol cleanup ticket.

## Commit Message

```text
T27: sanitize malformed tool-call protocol output
```
