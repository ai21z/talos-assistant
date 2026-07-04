# [T954-open-high] Release-staging attestations bind to workflow ref SHA, not staged candidate SHA

Status: open
Priority: high

## Evidence Summary

- Source: post-merge QA staging artifact inspection
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Candidate version / commit: `0.10.8` /
  `945f57e297990c23e966e35e7c923f611c105e73`
- Main merge commit / workflow ref: `1be606c1e81e23b58a93c46a023f4c759d5be50c`
- GitHub Actions run: `28714259463`
- Workflow: `.github/workflows/release-staging.yml`
- Downloaded artifact root:
  `local/release-artifact-smoke/run-28714259463-20260704-195918/`

The release-staging workflow succeeded and produced:

```text
qa-staging-talos-0.10.8-windows-x64
qa-staging-talos-0.10.8-linux-x64
```

Both staging manifests say the staged candidate SHA is `945f57e2...`:

```json
{
  "schema": "talos.releaseStagingManifest.v1",
  "releaseStatus": "qa-staging-not-a-release",
  "sha": "945f57e297990c23e966e35e7c923f611c105e73",
  "version": "0.10.8",
  "runId": "28714259463",
  "runAttempt": "1",
  "publicReleaseAssetsCreated": false
}
```

The workflow also really checked out and verified the requested candidate SHA
before building:

```yaml
with:
  ref: ${{ inputs.target_sha }}
```

However, GitHub artifact attestation verification fails when checked against
the staged candidate SHA:

```powershell
gh attestation verify Talos-0.10.8-windows-x64.msi `
  --repo ai21z/talos-assistant `
  --signer-workflow ai21z/talos-assistant/.github/workflows/release-staging.yml `
  --source-digest 945f57e297990c23e966e35e7c923f611c105e73
```

Observed:

```text
Error: expected SourceRepositoryDigest to be 945f57e297990c23e966e35e7c923f611c105e73,
got 1be606c1e81e23b58a93c46a023f4c759d5be50c
```

The same mismatch occurs for:

```text
Talos-0.10.8-windows-x64.msi
talos-0.10.8-windows-x64-app.zip
talos-0.10.8-linux-x64-app.tar.gz
SBOM predicate attestation for the Windows MSI subject
```

The same attestations verify successfully against the `main` merge commit:

```powershell
gh attestation verify <staged-file> `
  --repo ai21z/talos-assistant `
  --signer-workflow ai21z/talos-assistant/.github/workflows/release-staging.yml `
  --source-digest 1be606c1e81e23b58a93c46a023f4c759d5be50c
```

## Classification

Primary taxonomy bucket: `RELEASE_EVIDENCE`

Secondary buckets:

- `PROVENANCE`
- `PUBLIC_ARTIFACT_GATE`

Blocker level: public-artifact blocker

Why this level:

```text
Checksums and staged bytes are valid, and the workflow checked out the intended
candidate before building. The issue is provenance truth: current docs and
operator expectations say attestation can be verified against the staged
candidate SHA, but GitHub binds SourceRepositoryDigest to the workflow dispatch
ref SHA. Before public artifacts, the release process must make this identity
unambiguous and mechanically verifiable.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
GitHub artifact attestations use the workflow run's source repository digest
(`github.sha`) rather than the later `actions/checkout` target commit. Because
the workflow was dispatched with `--ref main` and `target_sha=945f57e2...`, the
attestation source identity is the main merge commit (`1be606c1...`) even
though the artifact payload was built from the checked-out candidate SHA.
```

Likely code/document areas:

- `.github/workflows/release-staging.yml`
- `docs/public-installation.md`
- `docs/user/release-channels.md`
- `src/test/java/dev/talos/release/CiWorkflowContractTest.java`

## Goal

```text
Release-staging provenance instructions and machine checks must truthfully bind
staged artifacts to the intended source identity. A reviewer should be able to
verify the staged artifacts without guessing whether to use the candidate SHA
or the workflow-ref SHA.
```

## Non-Goals

- No public GitHub Release.
- No tag creation.
- No winget publication.
- No weakening checksum, SBOM, or attestation requirements.
- No pretending attestations prove runtime behavior.

## Implementation Notes

Investigate and choose one explicit policy:

1. Dispatch release-staging on the exact target SHA/ref so GitHub attestation
   `SourceRepositoryDigest` equals the candidate SHA; or
2. Keep dispatching on `main`, but update manifests/docs/tests to record both
   identities:
   - `artifactBuildCheckoutSha`
   - `attestationSourceRepositoryDigest`

Do not leave the current mixed wording where `sha` means the checkout candidate
in the manifest, while `--source-digest` must use the merge/workflow SHA.

## Architecture Metadata

Capability:

- release staging and provenance

Operation(s):

- GitHub Actions artifact staging
- GitHub artifact attestation verification

Owning files:

- `.github/workflows/release-staging.yml`
- `docs/public-installation.md`
- release workflow contract tests

New or changed tools:

- none expected

Risk, approval, and protected paths:

- Risk level: high for release evidence, none for runtime mutation
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: staging manifest and docs name the exact digest to use
  for `gh attestation verify`
- Verification profile: workflow contract test plus a real staging rerun
- Repair profile: no runtime/model repair

Outcome and trace:

- Outcome/truth warnings: do not claim candidate-SHA attestation verification
  unless the command actually passes
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: workflow metadata, docs, contract tests
- Forbidden: release publication or tag creation

## Acceptance Criteria

- Staging docs no longer instruct reviewers to use the wrong digest.
- Staging manifest records enough identity to explain both:
  - the checked-out build source, and
  - the GitHub attestation `SourceRepositoryDigest`.
- Contract tests pin the selected policy.
- A fresh release-staging run has artifact attestations that verify exactly as
  documented.
- No public release assets are created while validating the fix.

## Tests / Evidence

Required deterministic coverage:

- `CiWorkflowContractTest` or equivalent pins the selected digest policy.
- Public installation docs contain the matching `gh attestation verify`
  command shape.

Required live CI evidence:

```powershell
gh workflow run release-staging.yml --repo ai21z/talos-assistant --ref <selected-ref> -f target_sha=<candidate-sha> -f version=0.10.8
gh run watch <run-id> --repo ai21z/talos-assistant --exit-status
gh attestation verify <downloaded-staged-file> --repo ai21z/talos-assistant --signer-workflow ai21z/talos-assistant/.github/workflows/release-staging.yml --source-digest <documented-digest>
```

## Known Risks

- GitHub may not expose a way for a workflow dispatched on `main` to make
  `SourceRepositoryDigest` equal a later checked-out commit. If so, the honest
  fix is dual-identity documentation and manifest fields, not a fake claim.

## Known Follow-Ups

- Consider adding a small release-provenance validation script that downloads
  artifacts and verifies checksums, SBOM shape, and attestations in one command.

## Implementation Status

Implemented locally on `v0.9.0-beta-dev` after commit
`9d7174ee9129c0a566d3a1656adf6d7894f54f5d`:

- `.github/workflows/release-staging.yml` now fails if `target_sha` does not
  match `${{ github.sha }}`, the digest GitHub binds into artifact
  attestations.
- Windows and Linux staging manifests now record:
  - `artifactBuildCheckoutSha`
  - `attestationSourceRepositoryDigest`
  - `attestationDigestPolicy`
- `docs/public-installation.md` now instructs reviewers to use
  `attestationSourceRepositoryDigest` for `gh attestation verify
  --source-digest` and states that `target_sha must match the workflow ref
  SHA`.
- `CiWorkflowContractTest` pins the selected strict same-SHA policy.

Status remains `open` until a fresh release-staging run proves the documented
attestation command succeeds against the newly generated artifacts.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest" --no-daemon
```
