# [T937-done-medium] Winget and signing release policy

Status: done
Priority: medium

## Evidence Summary

- Source: release-readiness review and public installation docs
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `docs/public-installation.md` says public Windows installers must be
    signed.
  - `tools/install-talos.ps1` refuses unsigned installer scripts unless
    `-AllowUnsigned` is passed.
  - Site/README mention planned winget install, but no public release artifact
    or winget manifest exists yet.
  - Microsoft winget packaging requires a manifest pointing at installable
    release assets.

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `RELEASE_HYGIENE`
- `OUTCOME_TRUTH`

Blocker level: release blocker before public Windows artifact or winget
publication

Why this level:

```text
Winget cannot be the first step. It points to release artifacts, so Talos must
first decide signing policy, publish a release asset, and only then submit a
manifest. Current docs/scripts are signed-public by default; an unsigned beta
would be a deliberate policy change. That policy decision affects any public
Windows artifact, not only winget.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Signing and winget should be treated as a release policy lane, not mixed into
runtime implementation work. The repo must choose between signed-only public
beta and explicitly labeled unsigned developer beta before publishing Windows
artifacts or changing installer behavior.
```

Likely code/document areas:

- `tools/install-talos.ps1`
- `docs/public-installation.md`
- `README.md`
- `site/index.html`
- release workflow

## Goal

```text
Define the signing and winget policy so public Windows artifacts, public docs,
installer behavior, and future manifests agree.
```

## Non-Goals

- No winget submission before GitHub Release assets exist.
- No silent relaxation of signature checks.
- No code-signing vendor integration unless explicitly approved.
- No public release in this ticket.
- No public Windows artifact publication until the signing/unsigned-beta policy
  is explicit.

## Architecture Metadata

Capability:

- Windows package-manager distribution

Operation(s):

- sign
- publish manifest
- verify installer identity

Owning package/class:

- release policy docs, installer script, future winget manifest

New or changed tools:

- possible winget manifest/wingetcreate workflow later

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: owner must choose signing policy
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: signature status, release asset URL, manifest identity
- Verification profile: installer hash/signature and winget validation
- Repair profile: unsigned assets cannot be described as signed

Outcome and trace:

- Outcome/truth warnings: public docs must not imply winget is live before
  manifest publication
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: docs/policy and installer guard changes
- Forbidden: broad packaging rewrite

## Acceptance Criteria

- Repo states whether first public Windows beta is signed-only or explicitly
  unsigned developer beta.
- Installer script behavior matches that policy.
- Docs/site/README distinguish planned winget from live winget.
- Winget is not attempted until a GitHub Release asset exists.
- Public Windows artifacts are not published until the signing/unsigned-beta
  policy is explicit and reflected in installer/docs behavior.
- If unsigned beta is allowed, docs clearly name the trust tradeoff and required
  install flag without weakening checksum verification.

## Tests / Evidence

Required deterministic regression:

- Unit test: public installer contract pins signature/checksum behavior.
- Integration/executor test: winget validation only when manifest exists.
- JSON e2e scenario: not applicable.
- Trace assertion: not applicable.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

## Known Risks

- Shipping unsigned public Windows binaries may damage trust even if labeled.
- Waiting for signing can delay first beta; this is a product/release decision,
  not a code correctness issue.

## Known Follow-Ups

- Acquire or configure signing before winget.

## Resolution

Policy decision:

```text
Windows public beta is signed-only. `-AllowUnsigned` is local
development/manual QA only, not a public beta install path.
```

Changes:

- `tools/install-talos.ps1` now reports `-AllowUnsigned` as a local
  development/manual QA escape hatch only.
- `README.md`, `docs/public-installation.md`, `docs/user/installation.md`,
  `docs/user/release-channels.md`, and `site/index.html` now agree that Windows
  public beta is signed-only and winget remains planned until signed GitHub
  Release assets and a manifest exist.
- `PublicInstallPackagingContractTest` pins the signed-only policy and the
  no-live-winget boundary across script, README, docs, and site.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
```

Result: BUILD SUCCESSFUL.
