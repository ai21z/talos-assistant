# T857 EngineRegistry Resolve Ollama-Catalog Symmetry

Status: implemented, awaiting review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Base commit: `556aa347d940816c372a42b5ed4d7a4583932af5`

## Decision

T857 completes the T855 default-path truth gate by applying the existing
Ollama-catalog skip policy to `EngineRegistry.resolve(...)` for bare model
names.

The qualified backend path remains unchanged:

- `resolve("ollama/<model>")` is still an explicit Ollama request and still
  queries the Ollama catalog.
- `resolve("<model>")` now skips the Ollama catalog unless the active backend
  is `ollama`.

## Code Change

- `EngineRegistry.resolve(String)` now filters the bare-name multi-catalog scan
  through `includeCatalogInDefaultInstalled(backend, activeBackend)`.
- The qualified `backend/model` branch is untouched.

This is intentionally the same policy T855 already applied to
`EngineRegistry.installed()`.

## Tests

`EngineRegistryInstalledCatalogPolicyTest` now pins the stronger behavior with
stub catalogs:

- bare-name resolve on active `llama_cpp` skips the Ollama catalog entirely and
  continues to a non-Ollama catalog;
- qualified `ollama/<model>` still uses the Ollama catalog;
- bare-name resolve includes the Ollama catalog when active backend is
  `ollama`.

The first test is intentionally ordered so the pre-T857 implementation would
have queried and selected the Ollama stub before the non-Ollama match. The test
therefore proves the catalog is not called, rather than only proving a predicate.

## Verification

Red run before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.core.engine.EngineRegistryInstalledCatalogPolicyTest" --no-daemon
```

Result: failed as expected in
`bareResolveSkipsOllamaCatalogWhenActiveBackendIsManagedLlamaCpp`.

Focused green run after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.core.engine.EngineRegistryInstalledCatalogPolicyTest" --no-daemon
```

Result: `BUILD SUCCESSFUL`.

## Out Of Scope

- No Ollama backend removal.
- No managed `llama.cpp` embeddings.
- No model download or cache migration.
- No `site/` edits.

