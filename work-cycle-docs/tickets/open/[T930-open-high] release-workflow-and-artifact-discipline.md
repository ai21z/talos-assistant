# [T930-open-high] Release workflow and artifact discipline

Status: open
Priority: high

## Evidence Summary

- Source: release-readiness review and local workflow/Gradle inspection
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `.github/workflows/beta-dev-ci.yml` has push/PR CI, but no tag trigger,
    release job, artifact upload, or GitHub Release creation.
  - `build.gradle.kts` defines Windows public beta tasks:
    `windowsReleaseMsi`, `windowsReleaseAppZip`,
    `windowsReleaseChecksums`, and `windowsReleaseArtifacts`.
  - `tools/install-talos.ps1` queries GitHub Releases `latest`, so it cannot
    work until a release exists.
  - GitHub CLI release docs define `gh release create` around a tag.
  - GitHub artifact-attestation docs describe provenance/integrity claims, but
    not a substitute for tests or release review.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `RELEASE_HYGIENE`
- `OUTCOME_TRUTH`

Blocker level: release blocker before public artifact publication

Why this level:

```text
The product currently has local Windows packaging tasks and a public installer
that expects GitHub Release assets, but no repeatable release workflow that
builds, verifies, checksums, uploads, and records artifact identity. Manual
asset handling would make the first public beta hard to audit and easy to
mislabel.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Release artifact staging should be an explicit workflow bound to a clean
candidate SHA and QA packet. It should build native staging artifacts on native
runners, keep them as non-release workflow artifacts until T929 passes, and
only then allow GitHub Release draft/prerelease assets. It records
checksums/provenance without pretending those replace tests.
```

Likely code/document areas:

- `.github/workflows/`
- `build.gradle.kts`
- `scripts/cut-candidate.ps1`
- `docs/public-installation.md`
- `work-cycle-docs/work-test-cycle.md`

Why a one-off patch is insufficient:

```text
Uploading a zip by hand would solve the immediate 404 but not the release
discipline problem. Talos needs repeatable artifact identity: the installer
downloads exactly the artifact built from the tagged/candidate SHA, checksums
match, docs point to the same version, and the release remains draft until QA
has passed.
```

## Goal

```text
Create a repeatable release staging and publication workflow for Talos that
cannot create GitHub Release assets or public assets without a clean candidate
identity and the release QA gate defined by T929.
```

## Non-Goals

- No public release/tag as part of designing the workflow.
- No draft GitHub Release asset before T929 passes.
- No winget submission in this ticket.
- No Linux deb/rpm/Homebrew packaging.
- No treating attestations or checksums as behavioral QA.
- No release from a dirty tree or unknown SHA.

## Implementation Notes

Recommended shape:

```text
1. Prefer a manual `workflow_dispatch` release-staging workflow for the first
   beta artifact, not a blind tag trigger. A tag trigger means the tag exists
   before artifact success is known.
2. The staging workflow should accept a commit SHA/version and upload only
   non-release workflow artifacts named `staging` or `qa-staging`.
3. The staging workflow must refuse to run if the version/changelog/candidate
   manifest do not match the requested SHA.
4. Windows staging artifacts build on Windows; Linux staging artifacts build on
   Linux after T931 defines the artifact lane.
5. Upload checksums alongside staging artifacts.
6. A separate publication step may create a draft/prerelease GitHub Release only
   after the T929 QA packet passes and is referenced by path or manifest id.
7. Add artifact attestations/SBOM only after the basic release workflow is
   reliable, or keep them clearly marked as provenance follow-up.
```

## Architecture Metadata

Capability:

- release artifact creation

Operation(s):

- build
- package
- checksum
- upload release assets

Owning package/class:

- GitHub Actions workflow and Gradle release tasks

New or changed tools:

- possible new `.github/workflows/release-staging.yml`
- possible later publication workflow or manual publication script

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: workflow must be manually dispatched or gated by protected
  release conditions
- Protected path behavior: no protected workspace reads expected

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: workflow logs, checksums, staging artifact names, commit
  SHA, version, QA packet reference before publication
- Verification profile: T929 QA gate before public assets
- Repair profile: failed artifact workflow does not publish; fix forward and
  rerun

Outcome and trace:

- Outcome/truth warnings: no "release ready" claim until draft assets and QA
  packet are reviewed
- Trace/debug fields: not runtime trace; release logs and checksums required

Refactor scope:

- Allowed: workflow/task additions and release docs
- Forbidden: changing Talos runtime behavior

## Acceptance Criteria

- A release workflow exists and is documented.
- The staging workflow builds Windows artifacts from the exact requested commit.
- The staging workflow uploads only non-release workflow artifacts before T929.
- Any publication workflow refuses to create GitHub Release draft/prerelease
  assets without a QA packet reference satisfying T929.
- The workflow produces checksums for every staging and release asset.
- Draft/prerelease is the default only after T929 passes and publication is
  explicitly requested.
- The public installer asset names and workflow asset names agree.
- A failed build leaves no public release artifact behind.

## Tests / Evidence

Required deterministic regression:

- Unit test: release packaging contract pins artifact names and checksums.
- Integration/executor test: workflow syntax validated where practical.
- JSON e2e scenario: not applicable.
- Trace assertion: not applicable.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

If workflow code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Known Risks

- Tag-triggered workflows can create a tag before artifacts are proven.
- Manual workflow dispatch can drift unless it verifies candidate SHA/version.
- Draft GitHub Release assets are still release assets for T929 purposes; using
  them as pre-QA staging would violate the release boundary.

## Known Follow-Ups

- Artifact attestations and SBOM generation after the first workflow is stable.
- Winget submission after a signed Windows artifact exists.
