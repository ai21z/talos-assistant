# [T924-open-high] Cut 0.10.7 candidate

Status: open
Priority: high

## Evidence Summary

- Source: release/candidate runbook
- Date: 2026-07-02
- Talos version / commit: starts at 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: pending T918-T922 completion

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

