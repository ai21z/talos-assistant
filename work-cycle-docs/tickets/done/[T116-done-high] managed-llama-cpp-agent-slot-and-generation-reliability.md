# T116 - Managed llama.cpp Agent Slot And Generation Reliability

Status: done
Severity: high
Area: managed llama.cpp / tool loop / audit reliability

## Problem

The T115 focused Qwen/GPT-OSS audit validated the new GPT-OSS Hugging Face model source path, but it also exposed a managed llama.cpp runtime-control problem.

Talos runs as a sequential CLI agent, but managed llama.cpp previously allowed the server to auto-select parallel slots and left generation unbounded unless the user manually supplied server arguments. In the T115 audit, llama.cpp auto-selected four slots for GPT-OSS 20B and later reported KV/context failures during required-tool turns. This made tool-loop reliability harder to reason about because a no-tool mutation failure could be mixed with timeout/context pressure rather than a clean model/tool-choice result.

## Scope

- Make the managed llama.cpp default agent path deterministic for Talos CLI use.
- Add an explicit Talos-managed server-slot policy, `--parallel 1` by default, unless the user explicitly configures an override.
- Add a bounded generation policy at managed server startup so required-tool failures do not run until timeout/context exhaustion.
- Preserve user-provided `server_args` behavior, but avoid silently duplicating conflicting `--parallel`, `-np`, `--predict`, `--n-predict`, or `-n` arguments.
- Ensure prompt-debug/provider-body capture remains accurate after the change.
- Ensure HTTP 500 context errors surface as engine/runtime failures, not as ambiguous model no-tool behavior when the backend explicitly failed.

## Implementation

- `LlamaCppServerManager` now adds managed-agent defaults:
  - `--parallel 1`
  - `--predict 2048`
- Defaults are skipped when equivalent user `server_args` already configure parallelism or prediction:
  - parallel aliases: `--parallel`, `-np`, including equals form.
  - prediction aliases: `--predict`, `--n-predict`, `-n`, including equals form.
- Compat HTTP 500 context-size responses remain typed `EngineException.ResponseError` failures.

## Verification

Focused tests:

```powershell
.\gradlew.bat test --tests dev.talos.engine.llamacpp.LlamaCppServerManagerTest --no-daemon
.\gradlew.bat test --tests dev.talos.engine.compat.CompatChatClientTest --no-daemon
```

Full verification:

```powershell
git diff --check
.\gradlew.bat test --no-daemon
.\gradlew.bat installDist --no-daemon
```

Audit:

- `local/manual-testing/t116-llama-cpp-runtime-control-audit-20260503-233238`
- Exact models:
  - Qwen Coder 14B: `qwen2.5-coder:14b`
  - GPT-OSS 20B: `gpt-oss:20b`
- No fallback model was configured or used.

Audit evidence:

- GPT-OSS llama.cpp server initialized `n_slots = 1`.
- Qwen llama.cpp server initialized `n_slots = 1`.
- The T115 GPT-OSS `Context size has been exceeded` server errors did not recur.
- GPT-OSS exact write succeeded with `COMPLETED_VERIFIED`.
- Failure-dominant output remained intact for static verification failures.

Findings report:

- `local/manual-testing/t116-llama-cpp-runtime-control-audit-20260503-233238/FINDINGS-T116-LLAMA-CPP-RUNTIME-CONTROL.md`

## Follow-Up

T117 was opened for a separate repair-framing issue found during the audit: static repair context can correctly identify `script.js` as a wrong similar target for required `scripts.js`, but then promote both paths into the full-file replacement target list.

## Non-Goals

- No model substitution.
- No return to Ollama for audit evidence.
- No full T61-style audit in this ticket.
- No broad prompt wording changes.
- No broad model-selection UI.
