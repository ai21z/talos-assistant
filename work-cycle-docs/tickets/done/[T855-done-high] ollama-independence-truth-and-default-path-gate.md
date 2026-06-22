# T855 Ollama Independence Truth And Default-Path Gate

Status: done
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

## Closeout Evidence

Verified by Opus (code + tests + full-check delta). Implementation commit
`985b2b66`. All six acceptance criteria met:

- BM25-only defaults: `default-config.yaml` `rag.vectors.enabled=false`,
  `embed.provider="disabled"`, `embed.model="none"`; code-level defaults moved
  `compat`->`disabled` consistently in `Config.ensureDefaults`,
  `EngineRuntimeConfig.from`, `EmbeddingsFactory.profileFrom`. This reconciles
  the bundled-default-vs-`SetupCmd`-generated divergence (both now BM25-only).
- No "via Ollama" surfaces: `RagIndexCmd` description rewritten,
  `tools.native_calling` comment de-Ollama'd, doc 23 rewritten to current truth
  (transport table matches `EmbeddingsFactory.createRawClient`, discloses the
  T856 gap). New `TrustClaimsHonestyTest` gate forbids "embeddings via Ollama" /
  "only ollama supported" / "Use Ollama's native tool API" across README,
  AGENTS, the user docs, doc 23, `default-config.yaml`, and `RagIndexCmd.java`.
- `/models` gate: `EngineRegistry.installed()` filters via
  `includeCatalogInDefaultInstalled` (ollama catalog skipped unless
  active backend is ollama). Verified the default chat path is independent of
  this anyway -- `RegistryLlmEngineResolver` only calls `select()`/`engine()`,
  never `installed()`/`resolve()`.
- `syncActiveModelIntoConfig`: writes `llm.default_backend`/`llm.model`, and
  `ollama.model` only for explicit ollama selections (2 new pinning tests).
- Dead code removed: `TerminalFirstRun` Ollama-probe helpers (no dangling
  refs) and the dead `META-INF/services/...ModelCatalog` file.
- T856 stays separate (no managed-embedding work).

Test integrity confirmed: every changed existing test legitimately tracks the
new BM25-only default and still pins real behavior (no assertion gutting);
legacy-path coverage untouched. Full `check`: 5306 tests, only the 2
pre-existing terminal/PTY environmental failures (`RootCmdTest:42`,
`StatusRowPresenterTest:40`, reproduce on parent `b462b168`), zero new failures;
count delta +4 matches the diff exactly.

Known out-of-scope residual (recommended follow-up, not a T855 defect): a bare
`/set model <name>` still reaches `EngineRegistry.resolve()`'s multi-catalog
scan, which probes Ollama on a non-ollama backend (fails safe). `installed()` is
gated but `resolve()` is not. The ticket scope is the `/models` installed-listing
path, which is met; a small follow-up should apply the same gate to the
bare-name `resolve()` scan for full symmetry. The dead `OllamaEmbedClient` /
`OllamaEngine.embed()` / `ModelEngine.embed` SPI cluster also remains (left
intentionally as the T856 embedding seam decision).
