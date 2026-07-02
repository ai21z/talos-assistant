# [T924-done-high] Cut 0.10.7 candidate

Status: done
Priority: high

## Evidence Summary

- Source: release/candidate runbook
- Date: 2026-07-02
- Talos version / commit: 0.10.7 / be5752867aee5176f3d7f0ee24482d983a5e9bf2 before ticket close
- Verification status: local candidate evidence repaired and installed smoke passed; remote Actions verification remains the next external check

Expected behavior:

```text
After stabilization tickets land, cut a named 0.10.7 candidate from a clean
committed tree using the scripted candidate loop. Do not publish a GitHub
release, tag, winget package, or installer release in this ticket.
```

Observed behavior:

```text
Current main still reports talosVersion=0.10.6 while CHANGELOG.md contains a
large Unreleased section above the 0.10.6 dated entry.
```

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Blocker level: release blocker

## Architectural Hypothesis

Architectural hypothesis:

```text
Version identity must precede candidate evidence. The existing
scripts/cut-candidate.ps1 encodes the required order: bump, commit, build,
launcher/SHA cross-check, check, wiki evidence close gate, quality summaries,
candidate manifest.
```

Likely code/document areas:

- `CHANGELOG.md`
- `gradle.properties`
- `scripts/cut-candidate.ps1`
- generated candidate evidence under `build/reports/talos/`

## Goal

```text
Produce a clean, pushed 0.10.7 candidate commit with local and GitHub Actions
evidence attached to the candidate identity.
```

## Non-Goals

- No GitHub release.
- No tag.
- No winget publication.
- No history rewrite.

## Architecture Metadata

Capability:

- candidate/release evidence

Operation(s):

- version bump, build, test, evidence generation

Owning package/class:

- release scripts and Gradle gates

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: not applicable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: git commit is the candidate checkpoint
- Evidence obligation: clean committed tree before evidence; candidate manifest after evidence
- Verification profile: scripted candidate loop plus GitHub Actions
- Repair profile: if candidate evidence fails, fix code and cut/rerun candidate evidence

Outcome and trace:

- Outcome/truth warnings: pre-bump checks are readiness only, not candidate evidence
- Trace/debug fields: candidate manifest SHA/version fields

Refactor scope:

- Allowed: none unless the script exposes a blocker.
- Forbidden: manual version edits that bypass the script.

## Acceptance Criteria

- T918-T922 are done and committed before candidate cut.
- `.\scripts\cut-candidate.ps1` completes successfully.
- `gradle.properties` reports 0.10.7.
- Candidate manifest records the current full SHA and 0.10.7.
- Candidate commit is pushed and GitHub Actions is green.
- Installed-product smoke is recorded after candidate install.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Commands:

```powershell
.\scripts\cut-candidate.ps1
git diff --check
```

Installed smoke:

```powershell
talos --version
talos doctor --start
```

## Completion Evidence

- `.\scripts\cut-candidate.ps1` bumped `talosVersion` to `0.10.7`, promoted
  `CHANGELOG.md` into `## [0.10.7] - 2026-07-02`, committed
  `1dcb3b43b1e0102b4097fd96e3ab680aea3572de`, and built `installDist`.
- The scripted run then failed in the mandatory post-bump `check` because
  `work-cycle-docs/wiki/CURRENT-STATE.md` still reported `Talos version:
  0.10.6`. This was a real evidence-lint failure caught before any push.
- Repaired forward in `be5752867aee5176f3d7f0ee24482d983a5e9bf2` by updating
  the wiki identity state to `0.10.7`.
- Re-run local candidate evidence after repair:
  - `.\gradlew.bat installDist --no-daemon` passed.
  - `.\build\install\talos\bin\talos.bat --version` reported `Talos 0.10.7`.
  - `.\gradlew.bat check --no-daemon` passed.
  - `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
  - `.\gradlew.bat talosQualitySummaries --no-daemon` passed and all four
    summary JSON files report version `0.10.7`.
  - Repaired `build/reports/talos/candidate-manifest.json` records version
    `0.10.7` and SHA `be5752867aee5176f3d7f0ee24482d983a5e9bf2`.
- Clean global install smoke:
  - `.\tools\install-windows.ps1 -Force` refreshed
    `%LOCALAPPDATA%\Programs\talos`.
  - `talos --version` reported `Talos 0.10.7`.
  - `talos status --verbose` started and reported the configured llama.cpp
    profile.
  - REPL `/status --verbose` and `/help` worked through installed `talos`.
  - `talos doctor --start` passed 8/8 checks, verified end-to-end model smoke,
    and released the managed server.
  - `talos setup models` rendered accepted beta stability profiles,
    experimental profiles, configure/test/guide steps, managed cache guidance,
    and user-owned GGUF guidance.
- Remote CI first run for `97087353bfbbeabbbcc05ea940b6b409e8408b0d`
  failed in `WikiLintStructuralTest` because the GitHub workflow used the
  default shallow checkout, so valid historical `last_verified_commit` objects
  were absent from the runner clone. The fix is workflow-owned: both
  `actions/checkout@v6` steps now set `fetch-depth: 0`, pinned by
  `CiWorkflowContractTest`, so the hard `git cat-file -e` wiki liveness check
  has full repository history in CI.
