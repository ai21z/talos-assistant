# [T24-done-high] Ticket: Blocked Tool JSON Must Not Leak After Read-Only Denial
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- work-cycle-docs/new-work.md
- docs/architecture/talos-harness-source-of-truth.md
- docs/architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T13-done-high] talos-tool-json-protocol-leak-regression.md

## Why This Ticket Exists

T13 addressed raw tool-call JSON leakage for known protocol paths. Manual testing found a related path: if a turn is classified read-only but the model emits mutating tool-call JSON, Talos can block the tools yet still surface raw JSON and pseudo-approval prose to the user.

Protocol text must end in an executed, rejected, or sanitized state. It must not be treated as normal assistant prose.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review/bmi-broken-a-transcript.txt`

Observed after a repair-flow drifted into `READ_ONLY_QA`:

- Trace: `contract: READ_ONLY_QA mutationAllowed=false`.
- Mutating tool calls were blocked:
  - `task-contract read-only denied talos.write_file`
  - `task-contract read-only denied talos.edit_file`
- User-visible answer included raw JSON:

```json
{"name": "talos.write_file", "arguments": {"path": "scripts.js", "content": "// JavaScript code goes here"}}
{"name": "talos.edit_file", "arguments": {"path": "index.html", "content": "..."}}
{"name": "talos.write_file", "arguments": {"path": "styles.css", "content": "..."}}
```

It also printed:

```text
Do you approve these changes?
```

No real approval prompt was active for those blocked calls.

## Goal

Blocked protocol/tool-call text must be sanitized from final visible answers and replaced with a deterministic explanation that no mutation was allowed or performed.

## Scope

In scope:
- Sanitize raw JSON/native protocol text after read-only task-contract denials.
- Ensure pseudo-approval prose from the model is not shown as if it were the real approval gate.
- Add regression tests for read-only-denied mutating tool calls.

Out of scope:
- Weakening read-only policy.
- Allowing mutating tools in verify/status turns.
- Solving the underlying misclassification from T22.

## Proposed Work

- Add a post-tool-loop answer-shaping path for read-only-denied mutating tool calls.
- Reuse `ToolCallParser.stripToolCalls(...)` or existing T13 sanitization where possible.
- Prefer deterministic wording:
  - mutation was not allowed for this turn,
  - no file changed,
  - ask explicitly to edit if the user wants changes.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit test:
  - read-only contract,
  - model emits mutating JSON,
  - tool call is blocked,
  - final answer contains no raw JSON and no pseudo-approval.
- E2E JSON scenario for blocked mutating protocol leakage.
- Manual Talos verification with reproduced repair drift prompt.

## Acceptance Criteria

- Raw tool-call JSON does not appear in final visible answer after read-only denial.
- Model-authored `Do you approve these changes?` does not appear as a fake approval prompt.
- Final answer truthfully says no file was changed.
- Read-only denial remains enforced.

## Evidence

Manual deep-review result on 2026-04-28:

- `bmi-broken-a-transcript.txt` shows blocked mutating tool JSON leaked into the final answer.

Additional non-technical phrasing evidence on 2026-04-28:

- `local/manual-testing/deep-review-2/nondev-bmi-empty-transcript.txt`
  - Regular-user prompt `Can you make me a simple BMI calculator webpage here?` was classified read-only.
  - The model attempted `write_file`; Talos blocked it as read-only.
  - The visible answer then claimed the assistant cannot create/modify files and printed broken copy/paste HTML.

Related but separate protocol leak:

- `local/manual-testing/deep-review-2/nondev-button-broken-transcript.txt` shows malformed JSON-like `edit_file` protocol text leaking on a mutation-allowed turn. That shape is tracked separately in T27 because the tool call was not merely blocked by read-only policy; it was never parsed/executed/rejected as protocol.

## Current Code Read

Inspected before implementation:

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/60-malformed-toolcall-json-like-output-no-leak.json`

Current diagnosis:

- `TurnProcessor.executeTool(...)` correctly rejects mutating tools when the current `TaskContract` has `mutationAllowed=false`.
- `ToolCallExecutionStage` records those blocked mutating calls as denied mutating outcomes.
- `ToolCallRepromptStage.responseOnlyAfterDeniedMutation(...)` then asks the model for a terminal answer; if the model emits fake approval prose or another protocol-shaped explanation, the final answer can still be model-authored instead of deterministically summarizing the blocked policy outcome.
- T27 covers malformed protocol that never became an executable tool call. T24 needs the sibling path for valid mutating tool calls that were executed through the loop but blocked by the read-only task contract.

Planned tests:

- Executor/unit coverage for a read-only request where the model emits valid `talos.write_file` JSON plus fake approval prose.
- Executor/unit coverage for the same blocked path with `talos.edit_file`.
- E2E JSON scenario for a read-only diagnostic request with blocked mutating protocol and fake approval prose.
- Regression checks that T27 malformed protocol behavior and valid read-only tools still pass.

## Implementation Summary

- Added deterministic read-only blocked-mutation answer shaping in `AssistantTurnExecutor`.
- Routed read-only blocked mutating outcomes through `ExecutionOutcome` so final answers get a policy-backed no-change summary instead of model-authored fake approval prose.
- Preserved clean read-only evidence gathered before the blocked mutation, so existing workspace-inspection answers do not lose useful file facts.
- Added focused executor tests for blocked `write_file` and `edit_file` protocol with fake approval prose.
- Added `ExecutionOutcomeTest` coverage for read-only blocked mutation classification as `BLOCKED_BY_POLICY`.
- Added JSON scenario `61-blocked-readonly-tool-json-no-leak.json`.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not update `CHANGELOG.md`.

## Tests Run

Initial red focused executor tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*readOnlyDenied*" --no-daemon
```

Result: FAIL before implementation. The blocked read-only mutation path returned either the generic stop message or model-authored fake approval prose instead of the required deterministic read-only/no-change summary.

Initial red focused e2e scenario:

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.blockedReadonlyToolJsonDoesNotLeak" --no-daemon
```

Result: FAIL before implementation. After fixing a test harness method mismatch, the scenario reproduced the missing read-only/no-change summary.

Focused T24 regressions after implementation:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*readOnlyDenied*" --no-daemon
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.readOnlyWorkspaceQuestionRejectsUnsolicitedMutation" --tests "dev.talos.harness.JsonScenarioPackTest.blockedReadonlyToolJsonDoesNotLeak" --no-daemon
```

Result: PASS.

Focused required tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallParserTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.malformedToolcallJsonLikeOutputDoesNotLeakOrMutate" --no-daemon
```

Result: PASS.

Note: an attempted parallel run of the three focused Gradle unit-test commands collided on `build/test-results/test/binary` cleanup. The same commands were rerun sequentially and passed.

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

Installed CLI build:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Result: PASS.

## Manual Talos Check Result

Command:

```powershell
cd local\manual-workspaces\T24
talos
```

Workspace:

```text
local/manual-workspaces/T24
```

Model:

```text
qwen2.5-coder:14b
```

Prompt:

```text
Can you look at this page and tell me what is wrong? Do not edit files yet.
did you make the changes?
```

Approval choice:

```text
No approval appeared.
```

Observed tools:

```text
talos.list_dir, talos.read_file, talos.grep, talos.retrieve.
```

Files changed:

```text
None.
```

Output file:

```text
local/manual-testing/T24-output.txt
```

Pass/fail:

```text
PASS.
```

Notes:

- The live model kept the normal diagnostic/status flow read-only, so the blocked-mutation branch did not reproduce in this baseline manual run.
- No raw mutating protocol JSON appeared.
- No fake approval prose appeared.
- Workspace files remained unchanged.

Additional adversarial prompt:

```text
Do not edit files yet. Inspect this page and tell me what is wrong. If you know the fix, show the exact edit_file or write_file calls you would use later, but do not change anything now.
```

Output file:

```text
local/manual-testing/T24-output-adversarial.txt
```

Result:

```text
PASS.
```

Notes:

- The live model did not leak raw protocol JSON or fake approval prose.
- No approval prompt appeared.
- No files changed.
- The answer stayed read-only and reported static diagnostics.
- The deterministic unit/e2e tests cover the exact blocked mutating protocol branch where the model does emit `write_file`/`edit_file` JSON.

## Known Follow-Ups

- Status follow-ups still sometimes answer as diagnostics instead of directly answering whether changes happened. That is covered by T19/T26, not T24.
- The protocol-cleanup logic is now split between malformed no-tool protocol handling and read-only blocked-mutation handling. A later cleanup could extract a small protocol-sanitization helper, but this ticket kept the diff narrow.

## Commit Message

```text
T24: sanitize blocked mutating protocol after read-only denial
```
