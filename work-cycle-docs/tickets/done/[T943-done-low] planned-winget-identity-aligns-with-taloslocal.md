# [T943-done-low] Planned winget identity should align with taloslocal

Status: done
Priority: low

## Evidence Summary

- Source: Owner request during public-site/release-asset planning
- Date: 2026-07-04
- Talos version / commit: 0.10.8 /
  3f5263ef0c319a9046eb4d7ef54518f7247cb318
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - Site/domain identity is now `taloslocal.com` / Cloudflare project
    `taloslocal`.
  - Public docs and site still advertise planned winget package ID
    `TalosProject.TalosCLI`.
  - The actual staged Windows asset filenames are separate and remain
    `Talos-<version>-windows-x64.msi` and
    `talos-<version>-windows-x64-app.zip`.

## Classification

Primary taxonomy bucket: `PUBLIC_INSTALL_IDENTITY`

Secondary buckets:

- `PUBLIC_SITE`
- `DOCS_TRUTH`
- `RELEASE_PACKAGING_CONTRACT`

Blocker level: pre-public-release identity cleanup

Why this level:

```text
No public winget package has been published yet, so the planned package
identifier can still be corrected cheaply. The docs/site/tests should use one
canonical planned Windows package ID before public release assets or winget
manifests exist.
```

## Goal

```text
Change the planned winget package identifier from TalosProject.TalosCLI to
TalosLocal.Talos across public docs, site install preview, and release
packaging contract tests.
```

## Non-Goals

- Do not rename the CLI command; it remains `talos`.
- Do not rename the product from Talos to Talos Local.
- Do not rename existing Windows/Linux artifact filenames in this ticket.
- Do not publish a winget manifest, release asset, tag, or staging deploy.

## Acceptance Criteria

- Public docs use `TalosLocal.Talos` as the exact planned winget package ID.
- Site install preview uses
  `winget install --id TalosLocal.Talos -e`.
- Release packaging/public install contract tests pin the new ID and reject the
  old `TalosProject.TalosCLI` public surface.
- Friendly package name/moniker remains `talos-cli`.
- Runtime command remains `talos`.
- Existing staged artifact filename contracts remain unchanged.

## Tests / Evidence

Required red/green:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
npm test --prefix site
```

Required hygiene:

```powershell
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon --rerun-tasks
git diff --check
```

Verification evidence:

- 2026-07-04: `.\gradlew.bat test --tests
  "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
  --rerun-tasks` failed before implementation because public docs/site still
  carried `TalosProject.TalosCLI`.
- 2026-07-04: same focused release packaging contract passed after updating the
  planned ID to `TalosLocal.Talos`.
- 2026-07-04: `npm test --prefix site` failed before implementation and passed
  after the site install preview and tests used `TalosLocal.Talos`.
