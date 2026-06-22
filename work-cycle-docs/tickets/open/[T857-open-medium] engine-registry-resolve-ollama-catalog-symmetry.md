# T857 EngineRegistry resolve Ollama-Catalog Symmetry

Status: open
Priority: medium
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Problem

T855 gated the `/models` installed-listing path: `EngineRegistry.installed()`
filters catalogs via `includeCatalogInDefaultInstalled(backend, activeBackend)`,
so the Ollama catalog is skipped (no `:11434` probe, no `ollama list` spawn)
unless the active backend is `ollama`.

The sibling resolution path was left ungated. `EngineRegistry.resolve(name)`,
when given a **bare** model name (no `/`), streams `find()` over **every**
catalog including `OllamaCatalog`, whose `find()` calls `installed()`, which does
an HTTP POST to `http://127.0.0.1:11434/api/tags` and then spawns
`ProcessBuilder("ollama", "list")` (both fail safe / swallowed). This path is
reached by `SetModelCommand` (`/set model <name>` ->
`compositeCatalog().find()` -> `resolve()`).

Net: on the default `llama_cpp` backend, a bare `/set model qwen2.5-coder:14b`
still probes and tries to spawn Ollama. `installed()` is gated; `resolve()` is
not (asymmetry). It is an explicit user action that fails safe, so it was not a
T855 blocker, but it is the last surprise Ollama touch on a user-driven path and
should be closed to finish the Ollama-independence truth story before T856.

## Scope

Apply the same catalog-skip policy to `resolve()`'s bare-name multi-catalog
scan, reusing `includeCatalogInDefaultInstalled(backend, activeBackend)`.

## Critical design nuance (do not implement without this)

- **Qualified `ollama/X` must keep working.** `resolve("ollama/X")` takes the
  qualified branch that targets the named catalog directly. That is an explicit,
  intentional Ollama request and must remain ungated. Do **not** apply the
  filter to the qualified branch.
- **Qualified `llama_cpp/X`** unchanged.
- **Bare `X` (no slash)** is the only path that changes: the multi-catalog scan
  skips the Ollama catalog unless the active backend is `ollama`. Consequence: a
  user wanting an Ollama model by bare name on a non-ollama backend now gets
  "Model not found" and must qualify it as `ollama/X`. This is an intentional,
  documented behavior change to `/set model <bare>` consistent with the T855
  opt-in posture (Ollama is explicit, name it explicitly).

## Non-Goals

- Do not remove Ollama or any Ollama class. Ollama stays an explicit optional
  backend.
- Do not change the qualified `backend/model` resolution path.
- Do not change `installed()` (already gated by T855).
- Not the managed `llama.cpp` embedding work (T856).
- Do not touch `site/`.

## Acceptance Criteria

- On a non-ollama active backend, `EngineRegistry.resolve(bareName)` does not
  invoke the Ollama catalog (no `:11434` probe, no `ollama list` spawn).
- `resolve("ollama/X")` still resolves against the Ollama catalog (qualified
  explicit request preserved).
- `resolve(bareName)` still resolves `llama_cpp` and other non-ollama models.
- When the active backend is `ollama`, bare-name `resolve()` includes the Ollama
  catalog (legacy preserved).
- A deterministic regression test pins the above. Preferred (stronger than the
  predicate-only coverage T855 added): a test seam that lets a stub catalog
  assert its `find()`/`installed()` is **not** called on a bare resolve while
  `llama_cpp` is active. If a clean seam is disproportionate to the change, a
  logic test mirroring `EngineRegistryInstalledCatalogPolicyTest` applied to the
  bare-name branch is acceptable, but document the weaker coverage.

## Architecture Metadata

- Capability ownership: model resolution / catalog scanning in
  `core.engine.EngineRegistry`.
- Operation type: catalog selection policy (read-only resolution).
- Risk: product-truth / default-path hardening; behavior-preserving except the
  documented bare-Ollama-name case. No file mutation.
- Trust relevance: removes a surprise external network probe and child-process
  spawn (`ollama list`) from a user-driven non-ollama path.
- Approval / protected-path / checkpoint behavior: not applicable.
- Evidence obligation: deterministic test plus focused suite; no `src/main`
  behavior change beyond the bare-name scan filter.
- Repair profile: preserve qualified resolution and the active-backend
  legacy path.

## Execution Order

Execute T857 before T856. T857 is a small symmetry completion of the T855 truth
gate; T856 (managed `llama.cpp` embeddings) is the larger vector-independence
feature and remains separate.

## Process

Trust-adjacent product-truth change. GPT implements, independent review verifies (code +
tests + the bare-name resolve behavior). No self-close.
