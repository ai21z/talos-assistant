# T193 - Context Budget Gate For Managed llama.cpp Turns

Status: done
Severity: high

## Evidence

Source audit:

- `local/manual-testing/llama-cpp-t61o-full-e2e-audit-20260507-162435/FINDINGS-LLAMA-CPP-T61O-FULL-E2E-AUDIT.md`

Concrete evidence:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:21616-21695`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:21701-21714`
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-57739.log:957`
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-57739.log:968`
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-57739.log:979`

## Problem

Long audit sessions could cause Talos to send a required mutation request larger than the active managed llama.cpp context. The server rejected Qwen requests with token counts above the 8192-token context.

This was a runtime prompt-assembly/control issue, not a model behavior issue.

## Completed

- Added a pre-send context-budget gate in `LlmClient` for structured engine chat requests.
- The gate uses the smaller of configured context and engine-reported context.
- Older non-system history is trimmed first.
- Current-turn capability frames, exact-write payloads, expected targets, latest user request, tool schemas, and current-turn tool context are preserved.
- If the current turn cannot fit after safe trimming, Talos throws a typed `EngineException.ContextBudgetExceeded` before any backend call.
- Context-budget failures render as failure-dominant user output and trace as `CONTEXT_BUDGET_EXCEEDED`.
- Trimmed provider requests carry a `context-budget-trimmed` prompt-debug tag.

## Verification

- `.\gradlew.bat test --tests dev.talos.core.llm.LlmClientContextBudgetTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.core.llm.LlmClientContextBudgetTest --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon`
- `.\gradlew.bat test --tests "dev.talos.core.llm.*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon`
- `.\gradlew.bat test --no-daemon`

