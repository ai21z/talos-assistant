# [T932-done-medium] Windows installer release handoff cleanup

Status: done
Priority: medium

## Evidence Summary

- Source: release-readiness review and installer inspection
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `tools/install-talos.ps1` defaults to GitHub Releases `latest`, verifies
    checksums, and installs the app-image ZIP.
  - `tools/install-talos.ps1` prints `talos setup models`.
  - Current docs scope `talos setup wizard` to Ubuntu/WSL x64; Windows remains
    on a user-provided `llama-server.exe` / `talos setup models` path unless a
    separate Windows wizard lane is designed.
  - `tools/install-windows.ps1` broadcasts `WM_SETTINGCHANGE`, but the public
    installer does not currently share that behavior.
  - `tools/install-windows.ps1` still prints stale `talos rag-index` and
    `talos rag-ask` suggestions.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `UX`
- `RELEASE_HYGIENE`

Blocker level: candidate follow-up before public Windows beta

Why this level:

```text
The Windows public installer is close, but its post-install guidance and shell
refresh behavior need to match the current Windows product surface. Stale
commands or a PATH refresh miss make the first run look broken even when the
artifact is valid.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Public and developer Windows installers should share the same user-facing next
steps where possible: installed Talos path, PATH refresh status, version check,
Windows-appropriate model setup path, and doctor/status commands. The public
installer should keep its signed/checksum release discipline and must not point
Windows users to the Ubuntu/WSL-only wizard unless Windows wizard support lands.
```

Likely code/document areas:

- `tools/install-talos.ps1`
- `tools/install-windows.ps1`
- `docs/public-installation.md`
- `README.md`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`

## Goal

```text
Align Windows installer post-install UX with the current Windows-supported
Talos setup path and remove stale command guidance before public beta artifacts
are published.
```

## Non-Goals

- No weakening Authenticode/checksum policy.
- No silent model/engine download on Windows.
- No winget submission.
- No Windows llama.cpp auto-install lane unless separately designed.
- No redirecting Windows users to `talos setup wizard` unless the wizard gains
  an explicit Windows lane with tests.

## Architecture Metadata

Capability:

- Windows public installation

Operation(s):

- install files
- verify checksum/signature
- update PATH
- print setup commands

Owning package/class:

- PowerShell installer scripts and release packaging contract tests

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: public installer must not silently download models
- Protected path behavior: user-local install path only

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: no workspace checkpoint
- Evidence obligation: direct installed `talos --version`, PATH behavior,
  next-step output
- Verification profile: Windows installed smoke
- Repair profile: stale command output fails tests

Outcome and trace:

- Outcome/truth warnings: post-install output must not advertise commands that
  no longer exist
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: shared installer message helper if useful
- Forbidden: broad packaging rewrite

## Acceptance Criteria

- Public Windows installer prints current Windows-appropriate setup guidance.
- If Windows remains outside the wizard lane, `talos setup models` is retained
  or clarified rather than replaced by `talos setup wizard`.
- Developer Windows installer no longer suggests `rag-index` or `rag-ask`.
- Public installer either broadcasts PATH change or clearly tells users which
  shell restart/action is required; prefer matching dev installer behavior if
  safe.
- Release packaging contract tests pin installer repo identity, checksum
  behavior, no blind script execution, and current setup handoff.
- Clean Windows installed-product smoke passes: `talos --version`,
  `talos status --verbose`, setup/help output.

## Resolution

- `tools/install-talos.ps1` now broadcasts `WM_SETTINGCHANGE` through
  `SendMessageTimeout` after adding the public `talos.cmd` shim directory to
  the user PATH.
- Public Windows bootstrap guidance remains Windows-appropriate:
  `talos --version`, `talos setup models`, `talos status --verbose`, and
  `talos`; it does not advertise the Ubuntu/WSL-only setup wizard.
- `tools/install-windows.ps1` no longer prints stale `talos rag-index` or
  `talos rag-ask` first-run commands; it points users at `talos setup models`
  and `talos status --verbose`.
- `docs/public-installation.md` now states that the Windows bootstrap
  broadcasts the environment change and tells users to open a new PowerShell
  window if the current shell cannot resolve `talos`.
- `PublicInstallPackagingContractTest` now pins the public and developer
  Windows installer setup handoff, PATH refresh behavior, and stale-command
  exclusions.

Note:

- Clean installed-product Windows smoke is still release/candidate evidence,
  not produced by this local script cleanup alone because no public Windows
  release artifact exists yet.

## Tests / Evidence

Required deterministic regression:

- Unit test: `PublicInstallPackagingContractTest` for post-install guidance.
- Integration/executor test: Windows installed smoke after artifact/staging
  install.
- JSON e2e scenario: not applicable.
- Trace assertion: not applicable.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

If installer scripts change:

```powershell
.\gradlew.bat check --no-daemon
```

## Known Risks

- Public installer currently enforces signed script behavior; changing beta
  signing policy would affect this ticket.
- Windows PATH refresh behavior can be inconsistent across terminals.

## Known Follow-Ups

- Signed release and winget manifest after artifact release discipline is in
  place.
