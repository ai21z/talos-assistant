# T197 - Context-Budget-Safe Continuation And Repair Retries

Status: done

Severity: high

Source audit: `local/manual-testing/llama-cpp-t61p-full-e2e-audit-20260507-180044/FINDINGS-LLAMA-CPP-T61P-FULL-E2E-AUDIT.md`

## Problem

The full llama.cpp T61P audit showed GPT-OSS hitting context-budget exceptions inside tool-loop continuation and missing-mutation retry paths.

The runtime contained the user-visible outcome, but it still attempted continuation/repair LLM calls that were already over budget. That is the wrong state-machine behavior: budget pressure should be handled before the provider call, either by compacting/fitting the request or by returning a deterministic typed runtime failure/skip reason.

## Evidence

Server log:

- `SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/talos.log:1`
  `Engine error during tool-call loop iteration 4: Request exceeds context budget: estimated 5637 input tokens, budget 5635 input tokens, context window 8192 tokens.`
- `SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/talos.log:2`
  `Engine error during tool-call loop iteration 1: Request exceeds context budget: estimated 5766 input tokens, budget 5635 input tokens, context window 8192 tokens.`
- `SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/talos.log:3`
  `Engine error during tool-call loop iteration 2: Request exceeds context budget: estimated 5856 input tokens, budget 5635 input tokens, context window 8192 tokens.`
- `SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/talos.log:4`
  `Missing-mutation retry failed: Request exceeds context budget: estimated 5946 input tokens, budget 5635 input tokens, context window 8192 tokens.`

User-visible containment:

- GPT-OSS static BMI failure was failure-dominant: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14222-14265`
- GPT-OSS later no-mutation repair/review was blocked: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:15665-15712`

Relevant code:

- `src/main/java/dev/talos/core/llm/LlmClient.java:974`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java:330`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java:2882`

## Scope

- Ensure tool-loop continuation, expected-target progress reprompts, static repair reprompts, and missing-mutation retries are context-budget-safe before making the LLM call.
- If a retry/continuation request cannot be fit safely, do not call the provider and do not rely on a caught `ContextBudgetExceeded` as normal control flow.
- Return a deterministic runtime-owned reason for the skipped/failed retry, for example `CONTEXT_BUDGET_RETRY_SKIPPED` or an equivalent typed failure.
- Preserve failure-dominant output when mutation or repair cannot continue.
- Record trace/debug evidence that the retry was compacted, trimmed, or skipped because of context budget.

## Non-Goals

- No broad prompt rewrite.
- No new provider abstraction.
- No change to normal successful tool-loop paths.
- No hidden increase of context window assumptions.

## Acceptance Criteria

- Add focused unit/integration tests for a tool-loop reprompt near the context budget.
- Add focused tests for missing-mutation retry near the context budget.
- Tests assert either:
  - the retry is compacted/fitted before the LLM call, or
  - the retry is deterministically skipped/failed before the LLM call with a typed reason.
- Tests assert that no success prose is emitted for budget-skipped mutation/repair outcomes.
- Trace/debug records the budget decision.
- Existing happy-path continuation and retry tests still pass.
- `.\gradlew.bat test --no-daemon` passes.
- `.\gradlew.bat build installDist --no-daemon` passes.
- A focused two-model llama.cpp re-audit shows no `Request exceeds context budget` warnings in Talos server logs for continuation/repair retry paths.

## Completion Notes

Implemented in code:

- `ToolCallRepromptStage` now catches `EngineException.ContextBudgetExceeded` before generic engine handling and turns it into a deterministic runtime transition.
- `LoopState` can breach an active pending action obligation with a specific detail, including context-budget detail.
- `AssistantTurnExecutor.mutationRequestRetryIfNeeded` now handles `ContextBudgetExceeded` as a typed missing-mutation retry failure instead of a generic retry exception.
- `ResponseObligationVerifier` now renders a runtime-owned context-budget retry-skipped answer.

Added tests:

- `ToolCallLoopTest.expectedTargetProgressContextBudgetExceededBecomesDeterministicBreach`
- `AssistantTurnExecutorTest.MutationRetryTests.mutationRetryContextBudgetExceededReturnsTypedDeterministicFailure`

Verification run:

- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressContextBudgetExceededBecomesDeterministicBreach --tests dev.talos.cli.modes.AssistantTurnExecutorTest$MutationRetryTests.mutationRetryContextBudgetExceededReturnsTypedDeterministicFailure --no-daemon` - passed
- `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon` - passed
- `.\gradlew.bat test --tests dev.talos.runtime.ToolCallLoopTest --no-daemon` - passed after rerunning sequentially; the first parallel attempt collided on Gradle test-results cleanup on Windows.
- `.\gradlew.bat test --no-daemon` - passed
- `.\gradlew.bat build installDist --no-daemon` - passed

Remaining validation:

- The next focused two-model llama.cpp re-audit should confirm no `Request exceeds context budget` warnings in server logs for continuation/repair retry turns.
