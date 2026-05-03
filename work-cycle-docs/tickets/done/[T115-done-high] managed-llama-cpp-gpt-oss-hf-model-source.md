# T115 - Managed llama.cpp GPT-OSS HF Model Source

Status: done
Severity: high
Area: managed llama.cpp / model setup / audit readiness

## Problem

The focused Qwen/GPT-OSS audit must use the exact audit models:

- Qwen Coder 14B: `qwen2.5-coder:14b`
- GPT-OSS 20B: `gpt-oss:20b`

Talos could run the Qwen side under managed llama.cpp, but the GPT-OSS side previously pointed at an installed Ollama blob. That blob reported GGUF architecture `gptoss`, while the llama.cpp-compatible GPT-OSS 20B repo reports architecture `gpt-oss`. Talos correctly failed fast and did not select a fallback model, but this blocked two-model audit evidence.

## Scope

- Add a managed llama.cpp model source option for Hugging Face GGUF repos, `engines.llama_cpp.hf_repo` and optional `engines.llama_cpp.hf_file`.
- When `hf_repo` is configured, start `llama-server` with the HF source flags instead of requiring `model_path`.
- Keep local `model_path` support unchanged.
- Keep the existing no-fallback behavior for incompatible local artifacts.
- Ensure status/health errors remain deterministic and actionable when neither local model path nor HF source is configured.
- Do not use any replacement model for GPT-OSS audit evidence.

## Acceptance

- Managed llama.cpp can build a server command for `hf_repo: ggml-org/gpt-oss-20b-GGUF` with model alias `gpt-oss-20b`, without requiring `model_path`.
- Optional `hf_file` is forwarded when configured.
- Local `model_path` command behavior remains unchanged.
- Unsupported local Ollama-style `gptoss` artifact still fails before process launch and still says no fallback model was selected.
- `/status --verbose` or top-level `talos status --verbose` surfaces the active engine state clearly enough to diagnose missing model source.
- Targeted tests cover HF source, local source, missing source, and unsupported local artifact.
- After implementation, rebuild/install Talos and rerun the focused Qwen/GPT-OSS audit using exactly `qwen2.5-coder:14b` and `gpt-oss:20b`.

## Completion Notes

- Implemented in `62ea73e feat: support llama cpp hf model sources`.
- Added config fields `engines.llama_cpp.hf_repo` and `engines.llama_cpp.hf_file`.
- Added managed command construction for `--hf-repo` and optional `--hf-file`.
- Preserved local `model_path` command construction and unsupported local `gptoss` fail-fast behavior.
- Added tests for HF source command construction, HF fallback model naming, local missing-source health wording, and runtime display model fallback.
- Rebuilt/installed Talos before the audit with `.\gradlew.bat installDist --no-daemon`.
- Ran the focused Qwen/GPT-OSS audit at `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633`.
- GPT-OSS audit used exact model identity `gpt-oss:20b` through `hf_repo: ggml-org/gpt-oss-20b-GGUF` and `hf_file: gpt-oss-20b-mxfp4.gguf`.
- No fallback model was configured or used.
- Follow-up runtime-control issue opened as T116.

## Non-Goals

- No model substitution.
- No return to Ollama as the audit engine.
- No full T61-style audit in this ticket.
- No broad model downloader UI beyond the llama.cpp-managed HF repo source.
