# T750 - Quality Gate Hardening

Status: open
Severity: medium
Release gate: no (gate-integrity hardening)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Three existing automated gates are dead or theater: the JaCoCo gate has a
~20-point slack and no BRANCH rule (the decision-heavy counter for a
fail-closed product is precisely the ungated one); CI triggers only on the
defunct `v0.9.0-beta-dev`... (now revived by the wave-0 fast-forward, but the
filter still excludes the working `codex/*` branches and `main`); and one
skipped test carries no recorded reason.

## Evidence Analysis

- `build.gradle.kts:939-951`: single rule, default INSTRUCTION counter,
  `minimum = 0.65`, with a stale comment claiming "current candidate coverage
  is ~71%". Plan-validation finding: a 0.80 floor **would fail `check` today**
  on at least one measured lane — actuals differ by lane/report
  (candidate-lane comment ~71% vs coverage-summary 84.8% instruction /
  64.7% branch), so floors MUST be set from a fresh measurement, not assumed.
- BRANCH coverage (64.73% per the regenerated 0.10.1 coverage-summary) is
  entirely ungated.
- `.github/workflows/beta-dev-ci.yml:3-9`: triggers pinned to
  `v0.9.0-beta-dev` only; job body is solid (test → e2eTest → jacoco/canary
  gates → check on windows runner). Local-first workflow means CI is dormant
  (no pushes), but a definition that can never fire is false confidence.
- `RagFlowSmokeTest.ask_doNotThrow`: bare `<skipped/>` in the test XML — no
  recorded reason, contradicting the evidence-accounting standard.

## Architectural Hypothesis

Ratchet-to-actuals: pin gates just under measured reality and tighten over
time — the same philosophy as the architecture ratchet's empty baseline.

## Architecture Metadata

Capability: build quality gates
Operation(s): n/a (build config + workflow + one test annotation)
Owning package/class: `build.gradle.kts` (jacoco verification),
`.github/workflows/beta-dev-ci.yml`, `dev.talos.core.rag.RagFlowSmokeTest`
New or changed tools: none
Risk, approval, and protected paths: n/a
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: the three named files

## Required Behavior

1. Measure first: run the candidate coverage lane on the current head and
   record actual INSTRUCTION and BRANCH percentages (bundle level) plus the
   per-package values for `dev.talos.runtime.policy`, `dev.talos.safety`,
   `dev.talos.core.secret`.
2. Set bundle floors ≈2 points below measured actuals (INSTRUCTION + new
   BRANCH rule); fix the stale comment with the measured numbers and date.
3. Add per-package floors for the three named packages at measured-minus-2
   (these are the packages where an untested branch violates doctrine).
4. Repoint CI triggers to `main`, `v0.9.0-beta-dev`, and `codex/**`
   (push + PR); rename the workflow file/name accordingly (e.g. `ci.yml`).
5. Annotate the RagFlowSmokeTest skip with an explicit reason
   (`@Disabled("...")` or `Assumptions.assumeTrue(cond, "reason")`) naming
   what un-skips it.

## Non-Goals

- No mutation/property testing this wave (Wave 6 roadmap).
- No new coverage; floors only formalize what exists.

## Tests

- `./gradlew.bat jacocoTestCoverageVerification --no-daemon` green at the new
  floors (proving they're correctly placed under actuals).
- `./gradlew.bat check --no-daemon` green.
- Workflow YAML parses (actionlint if available locally, else careful review).

## Acceptance Criteria

- New floors active and green; stale comment replaced with measured values.
- CI workflow triggers include the active branches.
- Skip reason visible in test XML/report output.
- CHANGELOG `## [Unreleased]` gains a T750 entry.
