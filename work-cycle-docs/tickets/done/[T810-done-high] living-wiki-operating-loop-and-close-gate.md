# T810 - Living Wiki Operating Loop And Close Gate

Status: done
Severity: high
Release gate: no - pre-Wave-5 wiki/report discipline hardening
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-13
Owner: unassigned

## Problem

T808 and T809 create a living evidence wiki, structural lint, and generated
report claim liveness checks. The remaining risk is operational: if the
evidence-liveness lint is only run manually, the wiki can still become
ceremonial and silently stale.

Talos needs an explicit close/candidate gate and a small evidence registry so
wiki claim blocks bind to regenerated evidence without duplicating raw paths or
registering stale reports that the gate cannot refresh.

## Required Behavior

1. Add `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon` as the
   explicit close/candidate wiki evidence gate.
2. Keep `wikiEvidenceCloseGate` outside the normal `check` task so Wave 5's
   frequent inner-loop checks do not force the full architecture intelligence
   report on every commit.
3. Exclude architecture intelligence report tests and generated-report wiki
   evidence-liveness tests from the default `test` task so `check` cannot pass
   or fail based on stale/generated report ordering.
4. Make the explicit wiki evidence gate clean and regenerate architecture
   intelligence evidence before liveness validation.
5. Wire `wikiEvidenceCloseGate --rerun-tasks --no-daemon` into
   `scripts/cut-candidate.ps1` after mandatory post-bump `check` and before
   `talosQualitySummaries`.
6. Add `work-cycle-docs/wiki/EVIDENCE-REGISTRY.md` with active entries only for
   generated architecture evidence refreshed by the close gate.
7. Migrate `CURRENT-STATE.md` generated-report claims to registry IDs.
8. Define the Talos wiki operating loop:
   Ingest, Query, Lint, Log, and Reject.
9. Define `trustTier` as an authority/provenance axis separate from the
   physical `evidence_inputs.type` source class.

## Non-Goals

- No Wave 5 refactor.
- No Talos runtime, CLI, product API, or project-memory behavior changes.
- No Qodana task, config, version, mode, or evidence mutation.
- No CodeQL, JFR custom event, Error Prone, NullAway, or JSpecify integration.
- No public template, article, Agent Skills packaging, embeddings, MCP search,
  or Obsidian graph tooling.
- No active registry entries for quality summaries until their producer belongs
  to the same liveness gate.
- No active registry entry for the wiki identity-freshness JSON because that is
  output from the evidence-liveness lint itself.
- No committed generated `build/` reports unless separately requested.

## Architecture Metadata

- Capability: living wiki operating loop and close/candidate evidence gate.
- Operation(s): deterministic wiki linting, generated architecture report
  regeneration, evidence registry validation, and candidate script sequencing.
- Owning package/class: `src/test/java/dev/talos/wiki`.
- New or changed tools: `wikiEvidenceCloseGate`.
- Risk, approval, and protected paths: no runtime workspace mutation; reads
  committed wiki pages, Gradle build script, candidate script, and ignored
  generated local build reports.
- Evidence obligation: wiki close/candidate work must run
  `wikiEvidenceCloseGate --rerun-tasks`.
- Outcome and trace: no product runtime outcome or trace behavior changes.
- Refactor scope: wiki docs, wiki tests, Gradle task wiring, candidate script,
  and this ticket only.

## Acceptance Criteria

- `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon` passes.
- `wikiEvidenceCloseGate` depends on `wikiLintWithEvidence`.
- `wikiEvidenceCloseGate` is not wired into the normal `check` task.
- `architectureIntelligenceReport` is not globally forced out-of-date.
- Default `test` excludes `dev.talos.architecture.intelligence.*` and
  `dev.talos.wiki.WikiEvidenceLivenessTest`.
- `wikiLintWithEvidence` cleans the architecture intelligence report output
  before regenerating and validating generated-report wiki claims.
- `scripts/cut-candidate.ps1` runs the close gate after post-bump `check` and
  before `talosQualitySummaries`.
- `EVIDENCE-REGISTRY.md` exists, is listed in `INDEX.md`, and contains one
  `talos.wikiEvidenceRegistry.v1` block.
- Active registry entries are exactly:
  `architectureIntelligence.runManifest` and
  `architectureIntelligence.wave5Sequence`.
- Registry structural lint validates schema, unique IDs, allowed source types,
  allowed trust tiers, and repo-relative normalized paths without requiring
  generated files to exist on a clean checkout.
- `CURRENT-STATE.md` generated-report claims use registry IDs instead of raw
  evidence paths.
- Quality summaries and wiki identity-freshness JSON are documented as deferred
  registry inputs, not active entries.
- T807, T808, T809, and T810 move to `done/` after owner review.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.wiki.WikiLintStructuralTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.wiki.WikiEvidenceLivenessTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.wiki.WikiCloseGateContractTest" --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
.\scripts\cut-candidate.ps1 -SelfTest
.\scripts\cut-candidate.ps1 -DryRun
.\gradlew.bat check --no-daemon
.\gradlew.bat test --tests "dev.talos.wiki.*" --no-daemon
git diff --check
git status --short
```

## Completion State

Completed after owner review. The close/candidate wiki gate exists, is not
wired into `check`, excludes generated-report liveness from the default `test`
task, regenerates architecture evidence before liveness validation, and is
wired into `scripts/cut-candidate.ps1`.

Completion evidence:

- `.\gradlew.bat check --no-daemon` passed after deleting generated
  architecture-intelligence evidence during review.
- `.\gradlew.bat wikiEvidenceCloseGate --no-daemon` regenerated missing
  architecture evidence and passed.
- `.\scripts\cut-candidate.ps1 -SelfTest` passed.
- `.\scripts\cut-candidate.ps1 -DryRun` prints
  `gradlew check -> wikiEvidenceCloseGate --rerun-tasks -> talosQualitySummaries`.
