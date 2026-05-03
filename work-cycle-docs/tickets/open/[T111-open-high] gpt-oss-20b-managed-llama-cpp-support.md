# T111 - GPT-OSS 20B Managed llama.cpp Support

Status: open
Severity: high
Area: backend/llama-cpp

## Problem

The focused managed llama.cpp audit used the requested `gpt-oss:20b` model, but the bundled/current llama.cpp binary failed to load it before readiness:

- `local/manual-testing/llama-cpp-qwen-gptoss-focused-audit-20260503-202119/RUNNER-LLAMA-CPP-GPT-OSS-20B.log`
- `local/manual-testing/llama-cpp-qwen-gptoss-focused-audit-20260503-202119/SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/llama_cpp-18082.log`

The server log reports:

- `general.architecture str = gptoss`
- `unknown model architecture: 'gptoss'`
- `main: exiting due to model loading error`

This means GPT-OSS 20B has not yet been validated through the managed llama.cpp product path.

## Scope

- Update or profile the managed llama.cpp runtime so the exact requested GPT-OSS 20B GGUF can load.
- If the local binary cannot support it, fail fast in preflight with a clear unsupported-architecture diagnostic before an audit begins.
- Keep audit policy restricted to `qwen2.5-coder:14b` and `gpt-oss:20b`.
- Do not substitute other models as audit evidence.

## Acceptance

- A managed llama.cpp smoke/preflight check can load `gpt-oss:20b`, or fails before the interactive audit with a clear unsupported-model reason.
- The diagnostic names the model alias/path and the unsupported architecture when available.
- The next focused audit artifact proves the exact `gpt-oss:20b` model was used.
- No fallback model is silently selected.

## Verification

- Targeted unit tests for unsupported managed llama.cpp model diagnostics.
- Managed llama.cpp smoke run with the exact GPT-OSS 20B local model.
- Focused two-model audit after T112, T113, and T114 are complete.
