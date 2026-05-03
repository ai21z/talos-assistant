# T112 - Engine Error Outcomes Are Failure-Dominant In Trace

Status: done
Severity: high
Area: runtime/trace

## Problem

Backend engine failures are visible in assistant output, but `/last trace` records them as successful recorded turns.

Evidence from the focused managed llama.cpp audit:

- GPT-OSS load failure:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:121-122` shows `EngineException$ConnectionFailed`.
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:144` records `Outcome: TURN_RECORDED`.
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:167` records `Status tag: OK`.
- Qwen context overflow:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1167` shows `EngineException$ResponseError: Engine error (HTTP 400)`.
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1175` records `Outcome: TURN_RECORDED`.
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1198` records `Status tag: OK`.

This weakens the failure-dominant discipline: a backend exception is not a normal completed or recorded assistant turn.

## Scope

- When an LLM/backend call throws under a normal, evidence, or mutation obligation, record a failure outcome in the local turn trace.
- `/last trace` and explain views must prefer that failure outcome over generic OK/TURN_RECORDED.
- Visible output should remain failure-dominant and contain no success prose.
- The fix should cover at least `EngineException.ResponseError` and `EngineException.ConnectionFailed`.

## Acceptance

- Tests simulate `ResponseError` and `ConnectionFailed` from `LlmClient`.
- For a mutating request, the final output contains the engine error and no `complete`, `ready to use`, or manual save/open prose.
- The local turn trace status is not OK and outcome is not `TURN_RECORDED`.
- `/last trace` renders the backend failure classification.
- Existing successful verified outputs still report complete/verified normally.

## Verification

- Targeted `AssistantTurnExecutorTest` and explain/trace tests.
- Targeted tool-loop tests if outcome propagation crosses the tool-loop boundary.
- Full `.\gradlew.bat test e2eTest --no-daemon` before closing.
