# T267 Live Two-Model Audit Results

## 1. Verdict

Not run. Release blocker remains.

## 2. Required models/backend

- `qwen2.5-coder:14b`
- `gpt-oss:20b`
- managed `llama.cpp` preferred, Ollama only as a legacy fallback if configured and stable

## 3. Environment check

`ollama list` was attempted and crashed with access violation `0xc0000005`.

The local Talos user config at `C:\Users\arisz\.talos\config.yaml` shows:

- default backend: `llama_cpp`
- configured model: `gpt-oss-20b`
- configured llama.cpp server path
- configured GPT-OSS GGUF model path

No Qwen profile/path was found in that config check.

## 4. Audit execution

No live prompt-bank prompts were executed in this pass.

## 5. Reason

The required two-model local backend pair was unavailable. Running only the configured GPT-OSS profile would not satisfy the release gate described in the runbook.

## 6. Required next step

Fix or confirm the local model setup for both Qwen and GPT-OSS, then execute `work-cycle-docs/reports/t267-live-two-model-audit.md` into a fresh ignored audit directory. Capture final answers, tool calls, traces, prompt-debug artifacts, provider bodies, session/turn logs, workspace diffs, and artifact canary scan results.

## 7. Release impact

Do not mark Talos private-document beta release-ready. Developer/text-project beta still requires the deterministic test gate to stay clean and product copy to avoid private-document claims.

