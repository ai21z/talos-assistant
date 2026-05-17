# T286 - Two-Model Local Backend Setup For Release Audit

Status: open
Severity: high / release gate
Release gate: yes - private-document beta and broad beta evidence
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

The required two-model live audit cannot pass the release gate until both local model backends are smoke-verified and the full prompt bank is executed from isolated audit configs.

## Evidence from current code

`scripts/run-t267-live-audit.ps1` now performs a reproducible preflight and writes `LIVE-AUDIT-PREFLIGHT.md` under `local/manual-testing/<audit-id>/`.

The preflight was corrected on 2026-05-16: Talos managed `llama_cpp` has one active `model_path` per config, so the release audit must run Qwen and GPT-OSS sequentially using isolated temp homes/config files instead of pretending both profiles live in one active config.

## Evidence from tests/audits

Previous preflight found:

- GPT-OSS profile configured.
- Qwen profile missing.
- managed llama.cpp signal present.
- Ollama legacy probe blocked.

Earlier audit notes recorded `ollama list` crashing with access violation `0xc0000005`.

2026-05-16 evidence:

- GPT-OSS GGUF file exists locally.
- Qwen GGUF file exists locally.
- Managed `llama.cpp` server path exists.
- 53 stale repo-owned `llama-server.exe` processes were stopped after Qwen failed with only 282 MiB free GPU memory.
- After cleanup, Qwen answered a model-forced smoke prompt (`QWEN_SMOKE_123`) through Talos using an isolated temp-home config.
- After cleanup, GPT-OSS answered a model-forced smoke prompt (`GPTOSS_SMOKE_123`) through Talos using an isolated temp-home config.
- Latest smoke evidence is `t267-live-audit-20260516-091319`; repo-owned stale server count after the run was 0.
- `checkRuntimeArtifactCanaries` passed on the smoke artifact roots.
- The focused beta-core capability live audit now runs both GPT-OSS and Qwen through `scripts/run-capability-live-audit.ps1 -BetaCoreOnly -StopStaleServers`.
- Latest focused beta-core audit: `capability-live-audit-20260516-210854`; both models completed 13 prompts, expected PDF/DOCX/XLSX reads were satisfied, and the targeted artifact canary scan passed.
- The focused helper uses an isolated config with explicit protected direct-read deny rules so unexpected protected reads fail closed without interactive approval prompts consuming later trace/debug commands.

## User impact

Without the two-model audit, deterministic tests cannot prove runtime behavior across model/tool/prompt interactions.

## Product risk

Talos must not be marked private-document release-ready without the live audit. Developer/text beta claims remain conditional on deterministic tests and no private-document positioning.

## Runtime boundary affected

Model backend, provider-body capture, prompt-debug, trace/session artifacts, tool result handoff, approval flow, RAG/retrieve, unsupported-format truthfulness.

## Non-goals

- Do not replace the two-model audit with a one-model run.
- Do not rely on broken Ollama if managed llama.cpp profiles are preferred.

## Required behavior

- Configure or generate isolated temp-home configs for `qwen2.5-coder:14b`.
- Configure or generate isolated temp-home configs for `gpt-oss:20b`.
- Prefer managed llama.cpp where supported.
- Preflight must report PASS before the prompt bank runs.

## Proposed implementation

Use sequential isolated configs for both managed llama.cpp models. Validate with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -PreflightOnly
```

Validate backend lifecycle and minimal model answers with:

```powershell
./gradlew.bat installDist --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -SmokeModels -StopStaleServers
```

## Tests

Run the live prompt bank from `work-cycle-docs/reports/t267-live-two-model-audit.md` after preflight passes.

## Acceptance criteria

- Preflight reports PASS.
- Both model-forced smoke prompts pass through Talos.
- Both models complete the prompt bank.
- `checkRuntimeArtifactCanaries` passes on the generated audit directories.
- Results are recorded in `work-cycle-docs/reports/t267-live-two-model-audit-results.md`.

## Remaining blockers

The focused beta-core capability bank has run. The broader private-document prompt bank and any approval-sensitive transcript still require either a synchronized prompt runner or a human-operated capture process.

## Open questions

Should approval-sensitive prompts remain human-operated, or should Talos add a synchronized prompt runner that can respond to approval prompts without risking stdin desynchronization?

## Related files

- `scripts/run-t267-live-audit.ps1`
- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`
