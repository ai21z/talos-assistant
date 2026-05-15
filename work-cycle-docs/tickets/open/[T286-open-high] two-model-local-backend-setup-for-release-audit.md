# T286 - Two-Model Local Backend Setup For Release Audit

Status: open
Severity: high / release gate
Release gate: yes - private-document beta and broad beta evidence
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

The required two-model live audit cannot run until both local model profiles and a stable backend are available.

## Evidence from current code

`scripts/run-t267-live-audit.ps1` now performs a reproducible preflight and writes `LIVE-AUDIT-PREFLIGHT.md` under `local/manual-testing/<audit-id>/`.

## Evidence from tests/audits

The current preflight found:

- GPT-OSS profile configured.
- Qwen profile missing.
- managed llama.cpp signal present.
- Ollama legacy probe blocked.

Earlier audit notes recorded `ollama list` crashing with access violation `0xc0000005`.

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

- Configure `qwen2.5-coder:14b`.
- Configure `gpt-oss:20b`.
- Prefer managed llama.cpp where supported.
- Preflight must report PASS before the prompt bank runs.

## Proposed implementation

Add or repair managed llama.cpp profiles for both models in the Talos user config or profile store. Validate with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -PreflightOnly
```

## Tests

Run the live prompt bank from `work-cycle-docs/reports/t267-live-two-model-audit.md` after preflight passes.

## Acceptance criteria

- Preflight reports PASS.
- Both models complete the prompt bank.
- `checkRuntimeArtifactCanaries` passes on the generated audit directories.
- Results are recorded in `work-cycle-docs/reports/t267-live-two-model-audit-results.md`.

## Remaining blockers

Qwen profile is missing in the inspected local config.

## Open questions

Where should model profile definitions live for release audit reproducibility: user config, repo-local sample config, or a dedicated audit config?

## Related files

- `scripts/run-t267-live-audit.ps1`
- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`
