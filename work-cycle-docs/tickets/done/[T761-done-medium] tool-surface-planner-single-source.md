# T761 - ToolSurfacePlanner Single-Source Defaults + Parity Pins

Status: done - completed in wave 2; see completion evidence section
Severity: medium
Release gate: no (advertised-vs-enforced surface integrity)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

`ToolSurfacePlanner` had two sources of truth: `plan()` (whose specs feed
the LLM's native tools AND the runtime allow-list
`rejectIfOutsideCurrentToolSurface`) and a ~46-line hand-maintained
`defaultVisibleToolNames()` copy of the same branch tree (feeding
capability-frame fallbacks, PromptInspector, traces when no runtime
context exists). Live drift found by the 2026-06-10 evaluation (roadmap
item W2.7): plan()'s expected-target-read branch (read-only contract with
expected targets → talos.read_file only) had NO counterpart in the
defaults, which fell through to the four-tool read surface - the model
could be advertised tools the runtime denies.

## Design

- New `runtime.toolcall.CanonicalToolDescriptors`: cached registry built
  from the same 13 tools TalosBootstrap registers (descriptor-only
  construction; nothing executes; RetrieveTool's service may be null).
- `defaultVisibleToolNames(contract, phase)` = `plan(contract, phase,
  CanonicalToolDescriptors.registry()).nativeToolNames()` - the
  production-enforced surface IS the advertised surface, by construction.
  The hand-maintained list block is deleted.
- Intentional asymmetry kept and documented: null contract → empty default
  frame, while plan(null, ...) returns the read-only enforcement surface.

## Behavioral delta

Read-only contracts with expected targets: ctx==null fallback frames now
advertise [talos.read_file] instead of [grep, list_dir, read_file,
retrieve] - matching what the runtime always enforced. Workspace-operation
defaults are now sorted name lists (previously intent-declared order);
no test pinned the old order.

## Tests / Evidence

- `ToolSurfacePlannerTest`: new drift-fix golden row (expected-target read
  defaults == [talos.read_file], the pre-T761 failing case); null-contract
  asymmetry pin (defaults empty, plan(null) read-only surface); the
  existing `defaultNamesMatchCurrentPromptFallbackSurfaces` golden rows
  pass unchanged - proving the derivation reproduces every non-drifted
  surface byte-for-byte.
- `ToolMetadataParityTest.canonicalDescriptorCatalogMatchesTheBootstrapRegistry`:
  the catalog cannot rot - names, risk levels, and full operation metadata
  must equal a bootstrap-equivalent registry (complements T757's golden
  metadata table, which already fails on any unpinned new registration).
- Full unit + e2e suites green (no prompt-audit snapshot pinned the
  drifted fallback frame).

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green.
- defaultVisibleToolNames is 4 lines + doc; the duplicated branch tree is
  gone.
