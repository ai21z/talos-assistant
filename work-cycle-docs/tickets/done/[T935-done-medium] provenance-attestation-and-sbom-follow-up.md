# [T935-done-medium] Provenance attestation and SBOM follow-up

Status: done
Priority: medium

## Evidence Summary

- Source: release-readiness review and external release best-practice research
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - GitHub supports artifact attestations for provenance/integrity claims.
  - GitHub documents that artifact attestations by themselves are not a
    behavioral test or complete security guarantee.
  - Talos release assets will need checksums first; provenance/SBOM should add
    audit value without blocking the first artifact workflow.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `RELEASE_HYGIENE`
- `AUDITABILITY`

Blocker level: future milestone unless release policy promotes it

Why this level:

```text
Checksums and release QA are mandatory for the first public artifact. SBOM and
artifact attestations are valuable, but adding them before the basic release
workflow is stable risks turning the first release arc into supply-chain
tooling sprawl.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Talos should add provenance and SBOM after release artifact generation is
repeatable. The system should treat attestations as evidence about how an
artifact was built, not proof that Talos behaves correctly.
```

Likely code/document areas:

- `.github/workflows/`
- `build.gradle.kts`
- release docs
- dependency/license reporting tasks if present

## Goal

```text
Add artifact provenance and SBOM support after checksummed release artifacts
are repeatable, without confusing supply-chain metadata with QA evidence.
```

## Non-Goals

- No blocking T929/T930 unless owner explicitly promotes this to release
  blocker.
- No replacing checksums.
- No replacing full QA.
- No broad dependency churn.

## Architecture Metadata

Capability:

- release provenance and dependency inventory

Operation(s):

- generate SBOM
- generate/verify artifact attestation
- publish metadata

Owning package/class:

- release workflow and build/reporting tasks

New or changed tools:

- possible GitHub artifact attestation step
- possible SBOM generation task/plugin

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: release workflow permissions must be explicit
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: attestation verification command and SBOM artifact
- Verification profile: release workflow dry run/draft release
- Repair profile: metadata failure should block provenance claim, not rewrite
  behavioral QA

Outcome and trace:

- Outcome/truth warnings: never claim "secure" solely because attestation exists
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: release workflow metadata steps
- Forbidden: runtime behavior changes

## Acceptance Criteria

- Release workflow can produce or attach an SBOM.
- Release workflow can produce artifact attestations or records why it cannot.
- Docs explain what checksums, SBOM, and attestations prove and do not prove.
- Verification command for attestations is documented.
- Behavioral release readiness remains owned by T929, not this ticket.

## Resolution

Implemented on `v0.9.0-beta-dev` during the 0.10.7-to-0.10.8 staging arc:

- Added a `releaseSbom` Gradle task that writes
  `talos-<version>-sbom.cdx.json` as a CycloneDX 1.6 JSON inventory of the
  resolved runtime classpath.
- Added `copyWindowsReleaseSbom` and `copyLinuxReleaseSbom`, and included the
  SBOM file in both Windows and Linux release checksum manifests.
- Updated `.github/workflows/release-staging.yml` to request explicit OIDC and
  attestation permissions, produce provenance attestations over staged files,
  and produce SBOM attestations for the primary package artifacts using
  `actions/attest@v4`.
- Updated public installation docs to state the boundary between checksums,
  SBOMs, artifact attestations, and behavioral QA, including `gh attestation
  verify` examples and the CycloneDX predicate type.

Deliberately not done:

- No GitHub Release, draft release, tag, winget publication, or public
  artifact publication.
- No new SBOM plugin or broad dependency churn.
- No claim that attestations prove Talos runtime behavior. T929 remains the
  release-readiness owner.

## Tests / Evidence

Deterministic regression:

- `PublicInstallPackagingContractTest` pins the SBOM task names, CycloneDX
  format metadata, runtime classpath inventory source, and checksum inclusion.
- `CiWorkflowContractTest` pins release-staging attestation permissions,
  `actions/attest@v4`, provenance/SBOM attestation inputs, and docs boundary
  wording.

Commands run:

```powershell
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest" --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
.\gradlew.bat releaseSbom --no-daemon
git diff --check
```

Required after build/workflow changes:

```powershell
.\gradlew.bat check --no-daemon
```

Local evidence:

- The focused release contract tests first failed on missing attestation,
  SBOM, and docs boundary behavior, then passed after implementation.
- `releaseSbom` produced
  `build/release/sbom/talos-0.10.7-sbom.cdx.json` with CycloneDX metadata and
  runtime components.

Not locally proven:

- GitHub-hosted attestation creation was not run locally. It requires the
  GitHub Actions OIDC/attestation environment and is verified by the workflow
  contract plus the next release-staging run.

## Known Risks

- Attestation language can become overclaiming if it implies behavioral safety.
- SBOM tooling can add complexity before the basic release lane is stable.

## Known Follow-Ups

- Consider SLSA level targets only after the first release workflow works.
