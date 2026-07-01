# T219 - Exact Current-Turn Writes Need Compact Context-Budget Fallback

Status: done
Severity: high

## Problem

The post-T218 broad llama.cpp audit found that a fresh explicit exact file-write request can fail before the backend call when old conversation history exceeds the selected local model context budget.

The failed turn was self-contained:

`Overwrite index.html with exactly AFTER. Use talos.write_file.`

Prompt audit showed the current-turn plan was correct: `FILE_EDIT`, mutation allowed, verification required, expected target `index.html`, and exact content `AFTER`. Active task context was cleared. The failure was not stale prompt construction or model behavior; Talos stopped before sending any provider request because the full conversation envelope did not fit.

## Evidence

Audit:

`local/manual-testing/llama-cpp-post-t218-broad-product-audit-20260508-042500/`

Key lines:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15315`
  - user asked: `Overwrite index.html with exactly AFTER. Use talos.write_file.`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15338`
  - `[Context budget exceeded: Talos could not safely fit this turn into the selected model context...]`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15353`
  - trace preview still shows the fresh current request.
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15359`
  - final output was deterministic context-budget failure.

Qwen passed the same exact-write probe:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:16383`
  - `[Static verification: passed - Exact content verification passed.]`

## Scope

- When the initial model call fails with `ContextBudgetExceeded`, attempt one compact current-turn fallback if and only if the current request is an explicit exact literal complete-file write.
- The compact fallback must include:
  - a short system instruction explaining this is a compact current-turn retry,
  - the current-turn capability frame with `[ExpectedTargets]` and `[ExactFileWrite]`,
  - the current user request.
- The compact fallback must exclude old conversation history and old static repair context.
- The compact fallback must narrow the tool surface to `talos.write_file`.
- If the compact fallback still exceeds budget, keep the existing deterministic context-budget failure.
- Do not use this fallback for deictic proposal apply, broad repair follow-ups, or tasks that need prior history.

## Acceptance

- Add a focused failing test where the first initial LLM call throws `ContextBudgetExceeded`, the current request has an exact literal write expectation, and a compact fallback writes the requested exact content.
- Assert the fallback backend request excludes old unrelated history.
- Assert the fallback backend request includes the current exact content expectation and expected target.
- Assert the fallback backend tool surface is only `talos.write_file`.
- Assert successful fallback output does not contain context-budget failure prose.
- Add a negative test proving non-literal/deictic mutation requests do not use this compact fallback.
- Existing exact-write, static repair, mutation retry, and context-budget tests still pass.
- Full Gradle build/install passes.

## Resolution Notes

Implemented a bounded initial-call fallback in `AssistantTurnExecutor`.

When the initial backend call fails locally with `ContextBudgetExceeded`, Talos now checks whether the current turn is an explicit exact literal write. If it is, Talos performs one compact current-turn retry that contains only:

- a short compact retry system instruction,
- the current-turn capability frame with expected targets and exact-file-write expectation,
- the current user request.

The fallback narrows the backend tool surface to `talos.write_file`, preserves provider request controls including required tool choice where supported, adds prompt-debug tag `context-budget-current-turn-fallback`, and records a trace action-obligation event with failure kind `CONTEXT_BUDGET_CURRENT_TURN_FALLBACK`.

The fallback is intentionally not used for deictic proposal-apply or other non-literal mutation requests.

## Tests

Passed:

- `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation' --no-daemon`
- `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --no-daemon`
- `.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --tests dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest --tests dev.talos.core.llm.AssistantTurnExecutorNativeToolSurfaceTest --tests dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest --tests dev.talos.runtime.ToolCallLoopTest --no-daemon`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat build installDist --no-daemon`
