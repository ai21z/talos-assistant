# T111 - GPT-OSS 20B Managed llama.cpp Support

Status: done
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

- Added targeted unsupported-model diagnostics:
  - Managed llama.cpp reads the GGUF `general.architecture` metadata before launch.
  - The known incompatible Ollama GPT-OSS blob architecture `gptoss` is rejected before `llama-server` starts.
  - The user-visible failure block and `/last trace` include the model alias, model path, unsupported architecture, and "No fallback model was selected."
- Local compatibility investigation:
  - Current llama.cpp release: `b9010`.
  - Official llama.cpp source/release uses GPT-OSS architecture name `gpt-oss`.
  - The installed exact Ollama `gpt-oss:20b` blob is GGUF but has `general.architecture = gptoss` and `gptoss.*` metadata.
  - A manual `--override-kv general.architecture=str:gpt-oss` probe then failed on missing `gpt-oss.context_length`.
  - A fuller metadata-key override then failed on missing tensor `blk.0.post_attention_norm.weight`.
  - Therefore this exact Ollama blob is not safe to treat as a llama.cpp-compatible GPT-OSS GGUF by string alias alone.
- Targeted tests:
  - `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.LlamaCppServerManagerTest.managedModeRejectsUnsupportedOllamaGptOssGgufBeforeLaunch" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*unsupported_model_connection_failure_is_visible_and_failure_dominant" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*ErrorHandling*" --no-daemon`
- Full verification:
  - `.\gradlew.bat test e2eTest --no-daemon`
  - `.\gradlew.bat installDist --no-daemon`
- Managed llama.cpp GPT-OSS fail-fast smoke:
  - Model: exact installed `gpt-oss:20b` Ollama blob.
  - Artifact: `local/manual-testing/t111-gptoss-failfast-smoke-20260503-211703/FINDINGS-T111-GPT-OSS-FAILFAST-SMOKE.md`
  - Result: deterministic unsupported-model failure before server launch; no `llama_cpp-*.log` was written.
- Focused two-model audit is still deferred until T114 is complete and a llama.cpp-compatible GPT-OSS 20B artifact decision is made. No fallback model was used as audit evidence.
