# T113 - Managed llama.cpp Context Budget For Required-Tool Turns

Status: open
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

- Unit tests for context option/profile selection or preflight diagnostics.
- Managed llama.cpp Qwen smoke/audit run with `qwen2.5-coder:14b`.
- Full test/e2e verification before closing.
