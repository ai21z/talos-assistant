# T855 Ollama Independence Truth And Default-Path Gate

Status: open
Priority: high
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Problem

Talos's beta product path is managed `llama.cpp`, with Ollama retained as an
explicit legacy backend. Several lower-level defaults and user-facing surfaces
still made Ollama look like part of the default path:

- bundled `default-config.yaml` enabled vectors and configured a placeholder
  `compat/talos-embed` endpoint even though no managed embedding server is
  wired yet;
- `Config` and `EngineRuntimeConfig` fell back to the same placeholder
  embedding profile;
- `/models` scanned every model catalog, so the default model-list path could
  probe or spawn Ollama even while `llama_cpp` was active;
- REPL bootstrap synced an active backend-qualified model into the legacy
  `ollama.model` block even for non-Ollama models;
- the stale embedding architecture reference still described the old
  Ollama-only embedding state.

## Scope

T855 is a product-truth and default-path hardening ticket. It does not remove
Ollama as an explicit optional backend and does not implement managed
`llama.cpp` embeddings.

## Acceptance Criteria

- Fresh bundled/setup defaults are BM25-only unless the user explicitly
  configures a local embedding endpoint.
- Current product/docs surfaces do not say default embeddings or vectors are
  "via Ollama".
- `/models` default installed-listing policy skips Ollama catalogs unless the
  active backend is `ollama`.
- `syncActiveModelIntoConfig` updates `llm.default_backend` and `llm.model`;
  it updates `ollama.model` only for explicit Ollama selections.
- Dead first-run Ollama probe helpers and the dead `ModelCatalog` service file
  are removed.
- T856 remains separate: managed `llama.cpp` embedding server support is not
  part of T855.

## Implementation Evidence

See
`work-cycle-docs/reports/t855-ollama-independence-truth-and-default-path-gate.md`.

T855 remains open pending review.
