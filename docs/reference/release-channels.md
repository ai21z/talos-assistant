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

## Upgrades

Talos does not currently have a `talos update` command. To upgrade, rerun the platform installer with `--force` and the pinned version.

Examples:

```powershell
powershell -ExecutionPolicy Bypass -File .\install-talos.ps1 -Version 0.10.8 -Force -AllowUnsigned
```

```bash
bash install-talos.sh --version 0.10.8 --force --no-wizard
```

The installer replaces the Talos app files. It does not intentionally remove the user's Talos home, model cache, or existing model configuration. Run `talos --version` after the upgrade to confirm the installed version.

For beta releases, prefer an explicit version such as `0.10.8`. The `latest` channel is reserved for stable non-prerelease releases.
