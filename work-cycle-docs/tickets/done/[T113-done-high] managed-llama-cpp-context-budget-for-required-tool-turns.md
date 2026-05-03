# T113 - Managed llama.cpp Context Budget For Required-Tool Turns

Status: done
Severity: high
Area: backend/llama-cpp, prompt-runtime

## Problem

Qwen Coder 14B loaded through managed llama.cpp and passed smaller required-tool turns, but the focused BMI create probes exceeded the default server context:

- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-18081.log:151-152` shows `n_ctx = 4096`.
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-18081.log:160` warns the full model capacity is not used.
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-18081.log:288-289` shows request `4383 tokens` exceeding `4096`.
- `SERVER-LOGS-LLAMA-CPP-QWEN-14B/llama_cpp-18081.log:299-300` shows request `4449 tokens` exceeding `4096`.

This blocks the normal prompt-construction probes before model behavior can be evaluated.

## Scope

- Add a managed llama.cpp context-budget strategy for the Qwen/GPT-OSS audit profiles.
- Prefer a safe larger context profile when memory allows.
- If a prompt would exceed the active context, Talos should trim/summarize bounded history or fail with a deterministic context-budget failure before backend HTTP 400.
- Prompt-debug output should make the context strategy visible enough to diagnose future failures.

## Acceptance

- The focused Qwen BMI create prompt sequence no longer fails with backend HTTP 400 caused by `request exceeds available context size`.
- If context cannot be increased or trimmed safely, the user sees a deterministic Talos context-budget failure, not an OK/TURN_RECORDED trace.
- Prompt debug or server diagnostics show the active context setting/strategy.
- No broad prompt rewrite or model substitution.

## Verification

- Added unit coverage for managed context floor, connect-only context passthrough, effective capabilities, and context-overflow trace classification.
- Targeted tests:
  - `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.LlamaCppServerManagerTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*llama_cpp_context_overflow_records_context_budget_failure_outcome" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.LlamaCppEngineProviderTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*ErrorHandling*" --no-daemon`
- Full verification:
  - `.\gradlew.bat test e2eTest --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`
- Managed llama.cpp Qwen smoke:
  - Model: `qwen2.5-coder:14b`
  - Artifact: `local/manual-testing/t113-qwen-context-smoke-20260503-205542/FINDINGS-T113-QWEN-CONTEXT-SMOKE.md`
  - The smoke intentionally configured `context: 4096`; managed llama.cpp launched with `n_ctx = 8192`.
  - The BMI create probe did not produce `request exceeds the available context size`.
