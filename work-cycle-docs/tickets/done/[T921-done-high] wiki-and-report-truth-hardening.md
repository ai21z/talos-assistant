# [T921-done-high] Wiki and report truth hardening

Status: done
Priority: high

## Evidence Summary

- Source: source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: wiki frontmatter SHAs are dangling/advisory; coverage report text hardcodes a stale 65% gate

Expected behavior:

```text
The living wiki should not carry dangling last_verified_commit claims, and
generated coverage reports should name the enforced 0.82 instruction gate.
```

Observed behavior:

```text
Multiple wiki pages contain last_verified_commit values missing from the current
repo, and build.gradle.kts report prose uses a 65% coverage gate despite the
enforced JaCoCo minimum being 0.82.
```

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Blocker level: candidate follow-up

## Architectural Hypothesis

Architectural hypothesis:

```text
Wiki lint should convert commit freshness from advisory to enforced for
last_verified_commit, while the wiki pages are refreshed in the same ticket so
the new hard gate does not fail immediately. Coverage report prose should derive
from the same instruction gate constant used by verification.
```

Likely code/document areas:

- `src/test/java/dev/talos/wiki/WikiLintStructuralTest.java`
- `work-cycle-docs/wiki/`
- `build.gradle.kts`

## Goal

```text
Wiki and generated report truth claims are current, hard-checked, and aligned
with enforced gates.
```

## Non-Goals

- No broad wiki rewrite.
- No coverage threshold change.
- No candidate cut in this ticket.

## Architecture Metadata

Capability:

- release evidence/wiki linting

Operation(s):

- validation and documentation update

Owning package/class:

- `dev.talos.wiki.WikiLintStructuralTest`, Gradle report generation

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: not applicable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: failing lint before wiki refresh/hardening, passing after
- Verification profile: wiki lint tasks and full check
- Repair profile: fail closed on dangling SHAs

Outcome and trace:

- Outcome/truth warnings: generated reports must not misstate gates
- Trace/debug fields: none

Refactor scope:

- Allowed: small lint/report constants.
- Forbidden: broad evidence-system redesign.

## Acceptance Criteria

- `last_verified_commit` values in required wiki pages resolve in the current repo.
- Wiki structural lint fails on future dangling `last_verified_commit` values.
- Coverage report prose uses the enforced 82% instruction gate.
- `wikiEvidenceCloseGate --rerun-tasks` passes after the update.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Completion Evidence

- Added a structural wiki lint assertion that every required page's
  `last_verified_commit` is a full SHA resolving through
  `git cat-file -e <sha>^{commit}`.
- Refreshed required wiki page frontmatter to an existing `main` commit and
  rewrote `CURRENT-STATE.md` away from stale `improvement/qodana-cleanup`
  topology to the public-main stabilization state.
- Corrected the generated coverage Markdown report to describe the enforced
  82% instruction gate instead of the stale 65% report prose.
- Added regression coverage for the generated coverage report text.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.wiki.WikiLintStructuralTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.build.QualityMarkdownReportsTaskTest" --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
```
