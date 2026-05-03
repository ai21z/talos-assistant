# T116 - Managed llama.cpp Agent Slot And Generation Reliability

Status: open
Severity: high
Area: managed llama.cpp / tool loop / audit reliability

## Problem

The T115 focused Qwen/GPT-OSS audit validated the new GPT-OSS Hugging Face model source path, but it also exposed a managed llama.cpp runtime-control problem.

Talos runs as a sequential CLI agent, but managed llama.cpp currently allows the server to auto-select parallel slots and leaves generation unbounded unless the user manually supplies server arguments. In the T115 audit, llama.cpp auto-selected four slots for GPT-OSS 20B and later reported KV/context failures during required-tool turns. This makes tool-loop reliability harder to reason about because a no-tool mutation failure can be mixed with timeout/context pressure rather than a clean model/tool-choice result.

Audit evidence:

- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/llama_cpp-18116.log:8`
- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/llama_cpp-18116.log:153-154`
- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/llama_cpp-18116.log:185-193`
- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/SERVER-LOGS-LLAMA-CPP-GPT-OSS-20B/llama_cpp-18116.log:270-379`
- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:100-142`
- `local/manual-testing/t115-hf-gptoss-focused-audit-20260503-223633/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:447-541`

Local llama.cpp help confirms:

- `--parallel N` / `-np N`: server slots, default `-1` / auto.
- `--predict N`: tokens to predict, default `-1` / infinity.

## Scope

- Make the managed llama.cpp default agent path deterministic for Talos CLI use.
- Add an explicit Talos-managed server-slot policy, likely `--parallel 1` by default, unless the user explicitly configures an override.
- Add a bounded generation policy for chat-completions requests or managed server startup so required-tool failures do not run until timeout/context exhaustion.
- Preserve user-provided `server_args` behavior, but avoid silently duplicating conflicting `--parallel`, `-np`, `--predict`, or `-n` arguments.
- Ensure prompt-debug/provider-body capture remains accurate after the change.
- Ensure HTTP 500 context errors surface as engine/runtime failures, not as ambiguous model no-tool behavior when the backend explicitly failed.

## Acceptance

- Managed llama.cpp command construction tests cover the default single-slot policy.
- Tests cover user override behavior for parallelism and generation caps.
- Tests cover no duplicate conflicting server arguments.
- Compat request or managed server tests cover bounded generation being sent/applied.
- Engine HTTP 500 context-size responses are classified clearly and do not become a misleading normal no-tool completion path.
- Rebuild/install Talos.
- Rerun the focused Qwen/GPT-OSS audit with exactly:
  - Qwen Coder 14B: `qwen2.5-coder:14b`
  - GPT-OSS 20B: `gpt-oss:20b`
- The rerun should show no llama.cpp `Context size has been exceeded` server errors.

## Non-Goals

- No model substitution.
- No return to Ollama for audit evidence.
- No full T61-style audit in this ticket.
- No broad prompt wording changes unless a failing test proves they are necessary.
- No broad model-selection UI.
