# [T835-open-high] Chat Transport Localhost Guard

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 high trust gap where embeddings enforce localhost policy but
the chat transport can send full prompts to a configured remote host.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`

## Scope

- Apply localhost enforcement to chat endpoints for `ollama.host`,
  `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, and `TALOS_ENGINE_HOST`.
- Default-deny non-localhost chat hosts.
- Allow remote chat only through an explicit `allow_remote=true` opt-in.
- Surface a clear warning when remote chat is explicitly allowed.

## Acceptance Criteria

- Non-localhost chat host configuration is rejected by default.
- Explicit allow-remote configuration permits the remote host and emits a
  visible warning.
- Tests cover both Ollama and llama.cpp style host resolution paths where the
  production code supports them.
- Localhost, `127.0.0.1`, and equivalent local endpoints continue to work.

## Non-Goals

- Do not change model provider selection semantics beyond locality enforcement.
- Do not claim air-gapped operation when a remote host is explicitly allowed.
