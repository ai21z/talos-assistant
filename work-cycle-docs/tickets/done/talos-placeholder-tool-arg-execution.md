# [done] Ticket: Placeholder Tool Argument Execution Guard

## Status: done

## Problem

Installed-CLI run in `local/playground/horror-synth-site` exposed a crash:

1. The model emitted planning narration mixed with template-style tool calls.
2. `read_file(path=<html-file-path>)` was parsed and dispatched to execution.
3. `Path.of("<html-file-path>")` threw `java.nio.file.InvalidPathException` (illegal char `<`).
4. The exception propagated uncaught through `ToolCallExecutionStage` → `ToolCallLoop.run()` →
   `AssistantTurnExecutor`, surfaced as "LLM call failed" and killed the entire turn.

Two structural gaps caused this:

**Gap 1 — Path-param placeholder not guarded for read-only tools.**
`TemplatePlaceholderGuard` already existed but was scoped inside `if (risk.requiresApproval())`.
`read_file` is `READ_ONLY` so `requiresApproval()` = false — the guard was skipped entirely.

**Gap 2 — No exception wrapping in `TurnProcessor.executeTool`.**
`toolRegistry.execute(call, toolCtx)` had no try/catch. Any unchecked exception from a tool
implementation propagated all the way to the top-level turn handler.

## Changes

### `TurnProcessor.java`
- Added `org.slf4j.Logger` (was previously missing).
- Added a **path-param placeholder guard** before the `requiresApproval()` block.
  Checks params: `path`, `file_path`, `filepath`, `file`, `filename`, `from`, `to` against
  `TemplatePlaceholderGuard.looksLikeTemplatePlaceholder()`.
  Fires unconditionally — applies to all tools regardless of risk level.
- Wrapped `toolRegistry.execute(call, toolCtx)` in try/catch `Exception`.
  On unexpected exception: logs at WARN level, returns `ToolResult.fail(ToolError.internal(...))`.
  Defense-in-depth: even if a future tool throws for reasons unrelated to placeholders,
  the exception is contained and converted to a directed error instead of killing the turn.

### `TurnProcessorPlaceholderGuardTest.java`
- Renamed `readOnlyToolWithPlaceholderLookingParamIsNotAffected` to
  `readOnlyToolWithPlaceholderPathIsNowRejected`. Flipped assertion to `assertFalse(r.success())`.
  The previous test asserted the now-stale behavior where read-only tool path params
  were not checked.
- Added `mutatingToolWithPlaceholderPathIsAlsoRejectedBeforeApproval` — verifies that mutating
  tools with a placeholder `path` value are rejected before the approval gate (same code path).
- Added `toolThrowingRuntimeExceptionProducesFailResultInsteadOfCrash` — uses a `ThrowingTool`
  helper that throws `RuntimeException`. Verifies `executeTool` returns `ToolResult.fail(...)`
  containing the original exception message, not an uncaught exception.
- Added `ThrowingTool` inner helper class (`READ_ONLY` descriptor, throws on every call).

## Tests

- All focused runtime tests: passed (6/6 in `TurnProcessorPlaceholderGuardTest`)
- Full `./gradlew test`: passed
- `./gradlew e2eTest`: passed

## What this does NOT fix

- The secondary hallucination failure (no tool reads, fake final answer) is a separate
  streaming no-tool fabrication issue tracked under
  `talos-streaming-no-tool-explicit-mutation-and-selector-grounding.md`.
- The pre-existing `ToolCallLoopP0Test.repromptsAfterPartialSuccessMixedMutationBatch` flaky
  failure is unrelated and was pre-existing before this change.
