# T267 Live Two-Model Audit Results

## 1. Verdict

PARTIAL. Release blocker remains.

The local backend setup blocker was reduced: both required model files exist and both models answered a minimal model-forced smoke prompt after stale repo-owned `llama-server.exe` processes were stopped. The full two-model prompt bank was not executed/classified, so this is not a passing live audit.

## 2. Required models/backend

- `qwen2.5-coder:14b`
- `gpt-oss:20b`
- managed `llama.cpp` preferred, Ollama only as a legacy fallback if configured and stable

## 3. Environment check

Prior environment check: `ollama list` was attempted and crashed with access violation `0xc0000005`.

Current preflight command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -PreflightOnly
```

Current cleanup/smoke command:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -SmokeModels -StopStaleServers
```

Previous preflight result:

- GPT-OSS profile configured: true
- Qwen profile configured: false
- Managed llama.cpp signal configured: true
- Ollama legacy backend probe: blocked, `ollama list` exited 2 with access violation `0xc0000005`
- Preflight verdict: BLOCKED

The local Talos user config at `C:\Users\arisz\.talos\config.yaml` shows:

- default backend: `llama_cpp`
- configured model: `gpt-oss-20b`
- configured llama.cpp server path
- configured GPT-OSS GGUF model path

That check was too narrow: Talos supports one active managed `llama_cpp.model_path` per config, so requiring both models in one user config is not the correct audit setup.

Updated preflight on 2026-05-16:

- Managed llama.cpp server path exists: true.
- GPT-OSS GGUF exists: true.
- Qwen GGUF exists: true.
- Existing repo-owned llama-server processes after cleanup: 0.
- Ollama legacy backend probe: available in the updated preflight, but managed llama.cpp remains the preferred backend.
- Preflight verdict: PASS.

Backend cleanup evidence:

- Before cleanup, Qwen startup failed because `llama-server` reported only 282 MiB free GPU memory.
- 53 stale repo-owned `llama-server.exe` processes were found and stopped.
- Latest preflight evidence, audit id `t267-live-audit-20260516-090643`: managed `llama.cpp`, GPT-OSS GGUF, and Qwen GGUF all present; repo-owned stale server count was 0.
- Latest smoke evidence, audit id `t267-live-audit-20260516-091319`: Qwen answered `QWEN_SMOKE_123` from an isolated temp-home config, GPT-OSS answered `GPTOSS_SMOKE_123` from an isolated temp-home config, and repo-owned stale server count after the run was 0.
- Targeted artifact scan passed on the smoke artifact roots:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t267-live-audit-20260516-091319,local/manual-workspaces/t267-live-audit-20260516-091319" --no-daemon
```

## 4. Audit execution

No full live prompt-bank prompts were executed/classified in this pass. The model-forced smoke prompts prove both local backends can answer through Talos with isolated configs, but they do not satisfy the release gate.

## 5. Reason

The required two-model local backend pair is now smoke-verified, but the full prompt-bank audit remains unrun.

## 6. Required next step

Execute `work-cycle-docs/reports/t267-live-two-model-audit.md` into a fresh ignored audit directory using sequential isolated configs for Qwen and GPT-OSS. Capture final answers, tool calls, traces, prompt-debug artifacts, provider bodies, session/turn logs, workspace diffs, and command output, then run:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<audit-id>,local/manual-workspaces/<audit-id>" --no-daemon
```

## 7. Release impact

Do not mark Talos private-document beta release-ready. Developer/text-project beta still requires the deterministic test gate to stay clean and product copy to avoid private-document claims.
