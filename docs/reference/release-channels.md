# Release channels

Talos uses three artifact states:

- local development builds from the repository
- QA staging artifacts created by release staging workflows
- public release artifacts attached to a GitHub Release

QA staging artifacts are not public release assets. They are review material for manual smoke, checksum verification, SBOM parsing, attestation verification, and installed-product checks.

No release tag should be published until the candidate has passing CI, staging artifacts, manual installed smoke, and the required model audit evidence for the release scope.

## What each state means

| State | Who should use it | What it proves |
|---|---|---|
| Local development build | Contributors and local QA | The current checkout can build and run locally. |
| QA staging artifact | Release reviewers | The workflow produced installable artifacts, manifests, checksums, SBOMs, and attestations for a named SHA. |
| Public release artifact | Beta users | The staged candidate passed the release gate and was intentionally published. |

Do not share QA staging artifacts as if they were public releases.
