# [T936-open-high] Cut 0.10.8 candidate with QA packet

Status: in-progress
Priority: high

## Evidence Summary

- Source: current branch state and release planning
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `CHANGELOG.md` has `[Unreleased]` entries after `0.10.7`, including T925,
    T926, and T928 work.
  - `gradle.properties` still reports `talosVersion=0.10.7`.
  - The owner selected the next candidate direction as `0.10.8`, not `0.11.0`,
    for this planning arc.
  - T929 defines the QA packet required before public release artifacts.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `RELEASE_HYGIENE`
- `OUTCOME_TRUTH`

Blocker level: release blocker before public beta artifact

Why this level:

```text
The branch contains material post-0.10.7 work. Reusing the 0.10.7 identity for
new artifacts would be false provenance. The next candidate should be cut as
0.10.8 and evaluated as its own evidence packet.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
0.10.8 should be a candidate cut, not an immediate public release. Its purpose
is to bind the current beta-dev work to a clean version/changelog identity and
then run the full automated plus manual QA gates before any public artifact.
```

Likely code/document areas:

- `gradle.properties`
- `CHANGELOG.md`
- `scripts/cut-candidate.ps1`
- `work-cycle-docs/wiki/CURRENT-STATE.md`
- QA packet location under `local/manual-testing/` or `work-cycle-docs/reports/`

## Goal

```text
Cut a clean 0.10.8 candidate from `v0.9.0-beta-dev`, run the full automated
candidate gates, then run the T929 manual PTY and large-scale installed-product
QA packet before any GitHub Release asset, draft release asset, signed asset,
tag-bound asset, winget-linked asset, or release-named artifact is created.
```

## Non-Goals

- No GitHub Release publication in this ticket.
- No draft GitHub Release asset in this ticket.
- No signed/release-named artifact in this ticket.
- No tag unless the release workflow policy explicitly says the candidate is
  ready for tagging after QA.
- No winget submission.
- No history rewrite.
- No skipping T929 because `check` passed.

## Architecture Metadata

Capability:

- candidate versioning and release evidence

Operation(s):

- bump version
- promote changelog
- build candidate
- run automated gates
- run installed-product QA
- record evidence

Owning package/class:

- candidate scripts and work-test-cycle docs

New or changed tools:

- none unless cut script needs QA-packet awareness

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: candidate cut requires owner release decision
- Protected path behavior: live audit must use synthetic protected fixtures

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: mutation live-audit lanes must prove checkpoint behavior
- Evidence obligation: candidate manifest plus T929 QA packet
- Verification profile: all automated gates, then manual PTY and two-model
  large-scale QA
- Repair profile: if QA fails, create/amend tickets, fix forward, and recut or
  rerun candidate evidence as appropriate

Outcome and trace:

- Outcome/truth warnings: do not call 0.10.8 release-ready until QA packet is
  complete and reviewed
- Trace/debug fields: manual QA requires `/last trace`, prompt-debug, and
  provider-body evidence where applicable

Refactor scope:

- Allowed: candidate docs/script guard improvements
- Forbidden: runtime feature work in the candidate cut commit

## Acceptance Criteria

- `0.10.8` candidate version is created only after T929 is amended/implemented
  and current open release blockers are either done or explicitly scoped out.
- `[Unreleased]` changelog entries are promoted to `0.10.8`.
- Candidate commit is clean and identifies branch/SHA/version.
- Automated gates pass from the candidate commit.
- Manual PTY gate passes against a clean installed product.
- Large-scale live QA runs for Qwen and GPT-OSS unless the release scope
  explicitly narrows model coverage.
- QA packet records all evidence paths and skipped coverage.
- Public release artifacts, draft GitHub Release assets, signed assets,
  tag-bound assets, winget-linked assets, and release-named artifacts remain
  blocked until T929 acceptance criteria are satisfied.

## Progress

2026-07-03 candidate-cut start:

- `scripts/cut-candidate.ps1 -SelfTest` passed.
- `scripts/cut-candidate.ps1 -DryRun` confirmed a clean
  `v0.9.0-beta-dev` tree at `0.10.7` and planned a `0.10.8` patch bump.
- `scripts/cut-candidate.ps1` created cut commit
  `420e6c92b1a5837caa1a1b4f2d79cacac6d9a165` with
  `talosVersion=0.10.8` and a promoted `CHANGELOG.md` `0.10.8` section.
- The scripted post-bump `installDist` step passed.
- The scripted post-bump `check` step failed as designed because
  `WikiLintStructuralTest` caught `CURRENT-STATE.md` still reporting
  `0.10.7`. This was an evidence-state failure, not a runtime/product failure.
- `CURRENT-STATE.md` was repaired forward to the `0.10.8` cut identity.

Remaining:

- Rerun automated candidate gates from the repaired 0.10.8 tree.
- Generate/review the candidate manifest and quality summaries from the
  repaired tree.
- Run the T929 manual PTY and two-model installed-product QA packet before any
  public artifact decision.

## Tests / Evidence

Required deterministic regression:

- Unit test: not required unless cut script changes.
- Integration/executor test: candidate script run.
- JSON e2e scenario: full manual QA references existing scenario banks where
  practical.
- Trace assertion: every natural-language live turn has `/last trace` evidence.

Commands:

```powershell
.\scripts\cut-candidate.ps1
git diff --check
```

After candidate cut:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
```

Manual QA must follow T929.

## Known Risks

- Running the candidate script before release blockers are done can create a
  noisy 0.10.8 packet that must be repaired forward.
- Manual QA after candidate cut can expose runtime bugs; those must create
  tickets and may require a new candidate packet.

## Known Follow-Ups

- T930 release workflow after 0.10.8 candidate QA is clean.
