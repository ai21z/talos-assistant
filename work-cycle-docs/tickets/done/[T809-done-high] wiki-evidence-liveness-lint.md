# T809 - Wiki Evidence-Liveness Lint

Status: done
Severity: high
Release gate: no - wiki/report discipline hardening
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-13
Owner: unassigned

## Problem

T808 creates the living evidence wiki and structural lint, but structural lint
only proves that the wiki shape is valid. It does not prove that a wiki claim
backed by generated report evidence is still live in the current generated
report payload.

Talos needs a small evidence-liveness lint before Wave 5 planning depends on
the wiki. The first version should validate selected generated architecture
report claims only.

## Required Behavior

1. Add a developer verification task:
   `.\gradlew.bat wikiLintWithEvidence --no-daemon`.
2. Run the architecture intelligence report and structural wiki lint before the
   evidence-liveness test.
3. Parse `talos-wiki-claims` fenced JSON blocks from wiki Markdown files.
4. Validate claim block schema `talos.wikiClaims.v1`.
5. Validate unique claim IDs, generated-report evidence type, generated report
   path boundary, JSON Pointer resolution, allowed operators, expected values,
   and generated-version equality with `gradle.properties`.
6. Require `CURRENT-STATE.md` to contain at least one claim block.
7. Produce failure messages that name the page, claim ID, evidence path, and
   JSON Pointer.
8. Keep branch, commit, and `last_verified_commit` freshness advisory rather
   than hard wiki claim gates.

## Non-Goals

- No Wave 5 refactor.
- No Talos runtime, CLI, product API, or project-memory behavior changes.
- No Qodana task, config, version, mode, or evidence mutation.
- No CodeQL, JFR custom event, Error Prone, NullAway, or JSpecify integration.
- No embedding, MCP, search, or Obsidian graph tooling.
- No external-source freshness verification.
- No Markdown table parsing.
- No committed generated `build/` reports unless separately requested.

## Architecture Metadata

- Capability: wiki evidence-liveness lint for generated architecture report
  claims.
- Operation(s): deterministic test-only validation of wiki claim blocks against
  generated JSON report artifacts.
- Owning package/class: `src/test/java/dev/talos/wiki`.
- New or changed tools: new Gradle verification task `wikiLintWithEvidence`.
- Risk, approval, and protected paths: no runtime workspace mutation; reads only
  committed wiki pages and ignored generated local build reports.
- Checkpoint, evidence, verification, and repair: evidence-liveness failures
  report the exact page, claim ID, generated report path, and JSON Pointer.
- Outcome and trace: no product runtime outcome or trace behavior changes.
- Refactor scope: wiki docs, test/reporting code, Gradle task, and this ticket
  only.

## Acceptance Criteria

- `.\gradlew.bat wikiLintWithEvidence --no-daemon` passes.
- `CURRENT-STATE.md` contains at least one `talos-wiki-claims` block.
- Claim blocks use schema `talos.wikiClaims.v1`.
- Claim IDs are unique across the wiki.
- Claim confidence is `DETERMINISTIC_GENERATED`.
- Evidence type is `generated_report`.
- Evidence paths remain under
  `build/reports/talos/architecture-intelligence/current/data/`.
- Evidence JSON files exist and JSON Pointers resolve.
- Operators are limited to `exists`, `notBlank`, `equals`, `contains`, and
  `equalsGradleProperty`.
- `CURRENT-STATE.md` does not hard-claim generated `/branch` or `/commit`.
- Generated `/talosVersion` is checked against `gradle.properties`, not a
  literal wiki value.
- `build/reports/talos/wiki-lint/current/identity-freshness.json` records
  advisory branch, commit, `last_verified_commit`, and version freshness.
- Missing generated reports fail explicitly and are not silently skipped.
- Qodana, CodeQL, JFR, nullness tooling, candidate release tasks, and Wave 5
  refactors are not started.

## Hardening Review

An adversarial review found three problems in the first T809 pass:

- volatile identity fields (`branch`, `commit`, and literal version) were
  hard-pinned in `CURRENT-STATE.md`;
- `confidence_histogram` was not parsed, and `min_confidence` was not defined as
  the lowest populated bucket;
- `architectureIntelligenceReport` read git/version identity without declaring
  those values as task inputs.

Chosen fixes:

- hard claim blocks keep structural generated-report facts only;
- generated version is checked against `gradle.properties` with
  `equalsGradleProperty`;
- branch and commit freshness are advisory evidence, not failing wiki claims;
- structural lint validates histogram keys/counts and `min_confidence`
  alignment;
- Gradle task inputs include wiki files, generated report data,
  `gradle.properties`, git HEAD, and git branch where applicable.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.wiki.WikiEvidenceLivenessTest" --no-daemon
.\gradlew.bat wikiLintStructural --no-daemon
.\gradlew.bat wikiLintWithEvidence --no-daemon
.\gradlew.bat architectureIntelligenceReport --no-daemon
.\gradlew.bat test --tests "dev.talos.wiki.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.intelligence.ArchitectureIntelligenceReportContractTest" --no-daemon
git diff --check
git status --short
```

## Completion State

Completed after owner review. The wiki evidence-liveness lint validates
generated-report claim blocks against regenerated architecture intelligence
JSON, keeps branch/commit freshness advisory, and avoids Qodana mutation.

Completion evidence:

- `.\gradlew.bat wikiEvidenceCloseGate --no-daemon` passed after T807-T810
  merge.
- `CURRENT-STATE.md` generated-report claims use registry-backed evidence IDs.
- Hard liveness checks cover schema, version equality with `gradle.properties`,
  and Wave 5 report path presence.
