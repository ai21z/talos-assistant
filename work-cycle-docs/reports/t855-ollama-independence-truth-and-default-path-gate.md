# T855 Ollama Independence Truth And Default-Path Gate

Status: implemented, awaiting review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Base commit: `b2a1e915668e710e1865f13744d54d088f5add15`

## Decision

Talos is not removing Ollama support in this ticket. T855 makes the default
product path honest:

- managed `llama.cpp` remains the default chat backend;
- retrieval defaults to BM25-only;
- vector retrieval requires an explicitly configured local embedding endpoint;
- Ollama remains an explicit legacy backend and explicit embedding provider.

## Code Changes

- `default-config.yaml` now ships `rag.vectors.enabled: false` and
  `embed.provider: "disabled"`, `embed.model: "none"`.
- `Config`, `EmbeddingsFactory`, and `EngineRuntimeConfig` now use
  disabled/none as the non-Ollama embedding fallback.
- `EngineRegistry.installed()` filters default installed-model catalog scans so
  Ollama is not queried unless the active backend is `ollama`.
- `TalosBootstrap.syncActiveModelIntoConfig(...)` now syncs active
  backend-qualified models into `llm.default_backend` and `llm.model`, and only
  writes `ollama.model` for explicit Ollama selections.
- Removed dead `TerminalFirstRun` Ollama helper methods and their tests.
- Removed the unused `META-INF/services/dev.talos.spi.ModelCatalog` resource.
- Updated `RagIndexCmd` wording and the embedding architecture reference to
  current code truth.

## Tests Added Or Updated

- `ConfigViewTest` pins default vectors as disabled.
- `EmbeddingsFactoryTest` pins default embedding profile as disabled/none while
  preserving explicit compat and explicit Ollama paths.
- `EngineRegistryInstalledCatalogPolicyTest` pins the default catalog policy:
  skip Ollama under managed `llama_cpp`, allow Ollama when selected.
- `TalosBootstrapTest` pins active model sync for managed and Ollama models.
- `TrustClaimsHonestyTest` bans current product surfaces from presenting Ollama
  as the default embedding or native-tool path.
- `RetrievalGoldContextHarnessTest` now describes the default config fixture as
  BM25-only.

## Out Of Scope

- T856 managed `llama.cpp` embeddings.
- Removing the Ollama chat backend.
- Removing explicit Ollama embedding support.
- Downloading or changing model files.
- Candidate recut, Qodana policy change, release metadata, or `site/` edits.

## Verification

Focused red run failed before implementation on the new catalog policy seam.

Focused green run after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.core.ConfigViewTest" --tests "dev.talos.core.embed.EmbeddingsFactoryTest" --tests "dev.talos.core.engine.EngineRegistryInstalledCatalogPolicyTest" --tests "dev.talos.cli.repl.TalosBootstrapTest" --tests "dev.talos.docs.TrustClaimsHonestyTest" --tests "dev.talos.cli.launcher.TopLevelStatusCmdTest" --tests "dev.talos.core.EngineRuntimeConfigTest" --tests "dev.talos.app.ui.TerminalFirstRunTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

Full `check` and `wikiEvidenceCloseGate` results should be recorded during
review before closing T855.
