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
- `work-cycle-docs/reports/t835-chat-transport-localhost-guard.md`

## Scope

- Apply localhost enforcement to chat endpoints for `ollama.host`,
  `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, and `TALOS_ENGINE_HOST`.
- Default-deny non-localhost chat hosts.
- Allow remote chat only through an explicit `allow_remote=true` opt-in.
- Surface a clear warning when remote chat is explicitly allowed.

Implementation note: T835 guards the final resolved chat host where production
already consumes one. Ollama currently consumes `TALOS_ENGINE_HOST`,
`TALOS_OLLAMA_HOST`, and `ollama.host`. llama.cpp currently consumes
`engines.llama_cpp.host`; adding new llama.cpp environment-host semantics is out
of scope for this locality fix.

## Acceptance Criteria

- Non-localhost chat host configuration is rejected by default.
- Explicit allow-remote configuration permits the remote host and emits a
  visible warning.
- Tests cover both Ollama and llama.cpp style host resolution paths where the
  production code supports them.
- Localhost, `127.0.0.1`, and equivalent local endpoints continue to work.

## Implementation Evidence

Open for review after implementation.

- Added `ChatHostLocalityPolicy` and provider-level enforcement for Ollama and
  llama.cpp chat/catalog endpoints.
- Added provider tests for default-deny, explicit remote opt-in, and loopback
  acceptance.
- Added a direct locality-policy test for loopback aliases and lookalike remote
  hosts.
- Updated README, AGENTS, and tracked docs to replace the T0 "no localhost
  guard yet" disclosure with the bounded post-T835 behavior statement.
- T835 remains open until reviewer closeout records broad gates and implementation
  commit SHA.

## Non-Goals

- Do not change model provider selection semantics beyond locality enforcement.
- Do not claim air-gapped operation when a remote host is explicitly allowed.
