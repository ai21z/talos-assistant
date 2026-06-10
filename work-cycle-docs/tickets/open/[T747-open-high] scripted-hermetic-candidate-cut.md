# T747 - Scripted Hermetic Candidate Cut

Status: open
Severity: high
Release gate: yes - removes the provenance-defect class found on the 0.10.1 cut
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

The candidate cut is a prose checklist executed by hand, and the 0.10.1 cut
violated its own rules four ways in one pass: a hand-extended **invalid full
SHA** in the release report, candidate summary JSONs left **stale at 0.10.0**,
the evidence chain left **uncommitted**, and the installed launcher **built 49
seconds before the candidate commit existed**. None were dishonesty — all were
transcription/sequencing errors that a script makes impossible.

## Evidence Analysis

- Wrong SHA: report cited `01646794b94f...903c`; `git cat-file` fatal; real
  HEAD `016467943b4b...182a` (fixed in commit `953bf4eb`).
- Stale summaries: `build/reports/talos/*-summary.json` said 0.10.0/June-7 at
  cut time; regenerated only during the evidence-repair pass.
- Pre-commit binary: launcher build 2026-06-10T06:59:25Z vs candidate commit
  07:00:14Z.
- `scripts/bump-patch.ps1` (79 lines, verified): bumps patch + promotes
  `## [Unreleased]` with hard validations (rejects placeholder notes, empty
  Unreleased, wrong section order) but performs **no commit, no build, no
  verification** — the gap this ticket fills around it.
- Mandatory post-bump check: AGENTS.md ("A pre-bump check ... is not candidate
  evidence") and work-test-cycle.md make the post-bump `gradlew check`
  mandatory.
- House PS pattern to mirror: `tools/manual-eval/run-talosbench.ps1`
  `-SelfTest` with `Assert-TalosBenchEqual`/`Assert-TalosBenchContains`
  helpers (fixture → parse → assert → throw).

## Architectural Hypothesis

Release engineering 101 (hermetic, self-service, policy-enforcing cuts): the
script encodes the candidate-loop doctrine so discipline stops being the
single point of failure.

## Architecture Metadata

Capability: release/candidate process automation
Operation(s): version bump, local commit, build, verification, summary
generation, manifest emission
Owning package/class: `scripts/cut-candidate.ps1` (new)
New or changed tools: none (process script; no runtime change)
Risk, approval, and protected paths: local git commit only; refuses dirty
trees; never pushes
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: emits `build/reports/talos/candidate-manifest.json`
Refactor scope: new script + (optional) docs pointer in work-test-cycle docs
(T751 owns doctrine text)

## Required Behavior

Sequence (each step fail-fast with a clear message):
1. Preconditions: `git status --porcelain` empty (else abort); on a branch.
2. `scripts/bump-patch.ps1` (its validations enforce changelog-before-bump).
3. `git add gradle.properties CHANGELOG.md` + commit `"Cut <X.Y.Z> candidate"`.
4. `.\gradlew.bat installDist --no-daemon`.
5. Cross-checks: `build\install\talos\bin\talos.bat --version` reports the new
   version; `git rev-parse HEAD` captured; gradle.properties matches.
6. Mandatory post-bump `.\gradlew.bat check --no-daemon`.
7. `.\gradlew.bat talosQualitySummaries --no-daemon`; verify all four
   summaries report the new version.
8. Emit `build/reports/talos/candidate-manifest.json`: version, FULL sha from
   git (never hand-typed), branch, launcher version line, summary statuses,
   ISO timestamps per step.
Flags: `-DryRun` (print plan, execute nothing mutating), `-SelfTest`
(fixture-based assertions of the parsing/manifest logic, no git/gradle).

## Non-Goals

- No pushes, no tags, no release-packet lane execution (separate runbooks).
- No replacement of bump-patch.ps1 (wrapped, not duplicated).

## Tests

- `pwsh scripts/cut-candidate.ps1 -SelfTest` green (manifest assembly,
  version parsing, failure messages).
- `-DryRun` on the wave branch prints the correct plan without mutating.
- Real execution exercised once at wave close (0.10.2 cut) — the dogfood gate.

## Acceptance Criteria

- SelfTest + DryRun green; script refuses a dirty tree (verified by test or
  manual demonstration with a scratch file).
- Wave-close 0.10.2 cut produces a manifest whose `sha` equals
  `git rev-parse HEAD` and all four summaries report 0.10.2.
- CHANGELOG `## [Unreleased]` gains a T747 entry.

## 2026-06-11 completion evidence

- `scripts/cut-candidate.ps1` landed: preconditions (clean tree, on-branch),
  bump via bump-patch.ps1, cut commit, installDist from the committed tree,
  launcher-version-vs-gradle.properties cross-check, mandatory post-bump
  `gradlew check`, `talosQualitySummaries` with per-summary version
  verification, manifest with 40-hex SHA validation (SHA from
  `git rev-parse`, never typed).
- `-SelfTest` PASS (version parsing incl. failure case, launcher-line
  accept/reject, manifest assembly + short-SHA rejection).
- Dirty-tree refusal proven live: the first `-DryRun` ran while the script
  itself was untracked and the precondition correctly aborted with the
  offending path listed.
- Real execution gate: the wave-close 0.10.2 cut (dogfood).
