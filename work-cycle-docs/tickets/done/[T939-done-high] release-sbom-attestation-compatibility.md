# [T939-done-high] Release SBOM attestation compatibility

Status: done
Priority: high

## Evidence Summary

- Source: GitHub Actions release-staging failure
- Date: 2026-07-03
- Talos version / commit: 0.10.8 /
  6714db7f100af386ee92f1020c54a180a3094642
- Branch: `main` failure, implementation to land first on
  `v0.9.0-beta-dev`
- Evidence:
  - Release staging run `28667031646` failed in the `Attest Windows SBOM`
    step while `Attest Windows QA staging files` succeeded.
  - The failed action was `actions/attest@v4` with
    `sbom-path: build/release/windows/talos-0.10.8-sbom.cdx.json`.
  - Error:
    `Unsupported SBOM format. Must be valid SPDX or CycloneDX JSON.`
  - The generated SBOM is parseable JSON and validates against the CycloneDX
    1.6 schema, but it omits `serialNumber`.
  - `actions/attest@v4` recognizes CycloneDX JSON by requiring
    `bomFormat`, `specVersion`, and `serialNumber`; schema-valid without
    `serialNumber` is therefore not sufficient for this GitHub attestation
    lane.

## Classification

Primary taxonomy bucket: `RELEASE_HYGIENE`

Secondary buckets:

- `AUDITABILITY`
- `VERIFICATION`

Blocker level: release-staging blocker

Why this level:

```text
The product runtime is not implicated, and the Windows artifacts built
successfully. The release staging lane still fails before upload because the
generated SBOM is not accepted by GitHub's attestation action. This blocks
repeatable QA staging and would make any provenance/SBOM claim false.
```

## Goal

```text
Make Talos release SBOMs compatible with GitHub's SBOM attestation parser while
keeping the SBOM deterministic and locally validated before the workflow asks
GitHub to attest it.
```

## Non-Goals

- No public GitHub Release, tag, winget publication, or release asset
  publication.
- No random UUIDs or nondeterministic SBOM bytes.
- No dependency on an external SBOM plugin during this stabilization fix.
- No weakening T929 behavioral QA or treating SBOM attestation as runtime
  proof.

## Architecture Metadata

Capability:

- release dependency inventory and SBOM attestation

Operation(s):

- generate SBOM
- validate release metadata
- attest staged artifacts

Owning package/class:

- `build.gradle.kts`
- `.github/workflows/release-staging.yml`
- release packaging contract tests

New or changed tools:

- `releaseSbom`
- new local SBOM validation task

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: GitHub workflow permissions remain explicit
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: local generated SBOM parse/format validation and a
  green GitHub release-staging run
- Verification profile: focused release contract tests, `releaseSbom`,
  release artifact staging task, GitHub Actions rerun
- Repair profile: fail before staging/upload when SBOM is not
  attestation-compatible

Outcome and trace:

- Outcome/truth warnings: do not claim public release or runtime correctness
  from this metadata fix
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: release SBOM generation, local validation task, packaging contract
  tests, changelog/ticket evidence
- Forbidden: runtime behavior changes, broad release workflow rewrite,
  dependency/plugin churn

## Acceptance Criteria

- `releaseSbom` writes a deterministic RFC-4122 `urn:uuid:...`
  `serialNumber`.
- The serial is deterministic for the same version/runtime dependency
  inventory; it must not be random per build.
- A local validation task checks the minimum GitHub SBOM attestation contract:
  JSON parseable, size less than 16 MiB, `bomFormat == CycloneDX`,
  `specVersion` present, and RFC-4122 `serialNumber` present.
- Windows and Linux release artifact tasks run the local SBOM validation before
  copying/attesting the SBOM.
- Focused release packaging tests fail before the fix and pass after it.
- Local `releaseSbom` and at least one local release artifact staging task pass.
- `v0.9.0-beta-dev` is pushed, merged into `main`, and the release-staging
  workflow is rerun successfully against the new main SHA.

## Tests / Evidence

Required deterministic regression:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --tests "dev.talos.release.CiWorkflowContractTest" --no-daemon
.\gradlew.bat releaseSbom validateReleaseSbom --no-daemon
.\gradlew.bat windowsReleaseArtifacts --no-daemon
git diff --check
```

Required hosted evidence:

```powershell
gh workflow run release-staging.yml --repo ai21z/talos-assistant --ref main -f target_sha=<new-main-sha> -f version=0.10.8
gh run watch <run-id> --repo ai21z/talos-assistant --exit-status
```

## Known Risks

- A schema-valid CycloneDX file can still fail GitHub's detector if the action
  requires additional recognition fields. Pin the action's minimum contract in
  local tests.
- A random serial would fix GitHub but make checksum/staging bytes unstable.

## Resolution

Implemented on `v0.9.0-beta-dev` for the 0.10.8 release-staging lane:

- `releaseSbom` now writes a deterministic RFC-4122 `urn:uuid:...`
  `serialNumber`, generated from the Talos root component identity and sorted
  runtime dependency inventory/hashes via `UUID.nameUUIDFromBytes`.
- Added `validateReleaseSbom`, which depends on `releaseSbom` and fails before
  staging when the SBOM is missing, empty, at or above 16 MiB, not a JSON
  object, missing `bomFormat=CycloneDX`, missing `specVersion`, or missing a
  valid UUID URN serial.
- `copyWindowsReleaseSbom` and `copyLinuxReleaseSbom` now depend on
  `validateReleaseSbom`, so both release artifact sets run the local GitHub
  SBOM attestation compatibility check before checksums/upload.
- `PublicInstallPackagingContractTest` now pins the deterministic serial, the
  validation task, the 16 MiB limit, and both OS SBOM staging dependencies.

Red/green evidence:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest.releaseBuildPublishesChecksummedCycloneDxSbom" --no-daemon
```

- Red: failed at `PublicInstallPackagingContractTest.java:108` because
  `releaseSbom` did not write the required deterministic `serialNumber`.
- Green: passed after adding the serial and validation task.

Focused verification:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --tests "dev.talos.release.CiWorkflowContractTest" --no-daemon
.\gradlew.bat releaseSbom validateReleaseSbom --no-daemon
.\gradlew.bat releaseSbom --rerun-tasks --no-daemon
.\gradlew.bat windowsReleaseArtifacts --no-daemon
```

Local SBOM evidence:

```text
serialNumber=urn:uuid:da093500-1473-3abe-98ec-9493d51c52de
size=27722
sha256=0727156c4b1ee735d127fe7d0fbffbd20855e83480e068ebda151f36764054c5
```

The forced rerun produced the same serial and SHA-256, proving the serial did
not introduce nondeterministic SBOM bytes.

Local staging note:

```text
windowsReleaseArtifacts
```

passed after temporarily adding the official no-install WiX v3.14.1 binaries
from `wix314-binaries.zip` to the process `PATH` under ignored
`build/local-tools`. The task produced the MSI, app ZIP, installer script,
checksums, and staged SBOM locally.
