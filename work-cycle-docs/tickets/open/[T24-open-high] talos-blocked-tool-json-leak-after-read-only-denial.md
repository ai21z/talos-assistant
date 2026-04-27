# [T24-open-high] Ticket: Blocked Tool JSON Must Not Leak After Read-Only Denial
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- work-cycle-docs/new-work.md
- docs/new-architecture/talos-harness-source-of-truth.md
- docs/new-architecture/talos-harness-plan.md
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
