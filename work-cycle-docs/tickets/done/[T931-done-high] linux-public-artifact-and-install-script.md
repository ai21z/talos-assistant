# [T931-done-high] Linux public artifact and install script

Status: done
Priority: high

## Evidence Summary

- Source: WSL setup work, release-readiness review, Gradle/application plugin
  inspection
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `tools/install-unix.sh` installs from a local built distribution and is
    developer/source oriented.
  - `docs/public-installation.md` states Linux is currently source/developer
    setup and no DEB/RPM/Homebrew/SDKMAN/JBang package is promised.
  - Gradle application plugin provides `distTar`/`distZip`, but there is no
    Talos public Linux artifact/checksum lane equivalent to Windows
    `windowsReleaseArtifacts`.
  - T926 proved Ubuntu/WSL x64 setup wizard and pinned llama.cpp/model setup
    from an installed Linux Talos path.

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `RELEASE_HYGIENE`
- `VERIFICATION`

Blocker level: release blocker before advertising Linux one-command install

Why this level:

```text
Talos can be installed and verified on WSL/Linux from a locally built
distribution, but there is no public downloadable Linux artifact or public
install script that fetches, verifies, installs, and then hands off to the T926
wizard. Advertising Linux install before this exists would recreate the
"source checkout required" gap.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The first public Linux lane should be narrow: Ubuntu/WSL x64, CPU-only,
developer beta, GitHub Release artifact, checksum verification, user-local
install, then `talos setup wizard`. More package formats can come later.
```

Likely code/document areas:

- `build.gradle.kts`
- `tools/install-unix.sh`
- possible new `tools/install-talos.sh`
- `docs/public-installation.md`
- `README.md`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`

Why a one-off patch is insufficient:

```text
T926 solved setup after Talos is installed. It did not create a public Linux
artifact distribution channel. A copy-paste command for users must verify the
downloaded Talos artifact, avoid hidden package/model installs, respect shell
profile behavior, and then call the wizard explicitly.
```

## Goal

```text
Provide a public Linux beta artifact and install script for Ubuntu/WSL x64 that
can be downloaded from GitHub Releases, checksum-verified, installed under the
user account, and handed off to `talos setup wizard`.
```

## Non-Goals

- No DEB/RPM/AppImage/Homebrew/SDKMAN/JBang in this ticket.
- No macOS lane.
- No ARM lane.
- No silent Java/package-manager/model/engine installation.
- No dynamic unpinned llama.cpp latest.
- No winget work.

## First Artifact Decision

Implement exactly one first Linux artifact shape:

```text
Primary target: runtime-bundled Linux app-image tarball produced on the chosen
Ubuntu runner. This avoids Java prerequisite friction for public install and
matches the "paste one command, Talos runs" product goal.

Fallback only if the primary target fails a focused proof with recorded
evidence: BYO-JDK `distTar` with explicit Java 21 preflight. This is simpler
but weaker as a public install experience.
```

The ticket must not blur these lanes. Start with the runtime-bundled lane. Use
the BYO-JDK fallback only after documenting why Linux `jpackage` app-image is
not viable for the first beta lane. If BYO-JDK is chosen, docs and installer
must say Java 21 is required before Talos launch. If runtime-bundled is chosen,
the installer must not ask the user to install Java for that lane.

## Architecture Metadata

Capability:

- Linux public installation

Operation(s):

- download artifact
- verify checksum
- install user-local files
- update shell profile
- run `talos --version`
- hand off to setup wizard

Owning package/class:

- Gradle distribution tasks and Unix install script

New or changed tools:

- possible new public `tools/install-talos.sh`
- possible new Gradle `linuxReleaseArtifacts`

Risk, approval, and protected paths:

- Risk level: high for distribution; medium runtime risk
- Approval behavior: no hidden package/model installs; explicit wizard prompts
  after Talos install
- Protected path behavior: install path limited to user-local Talos locations

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: profile update idempotent; no workspace mutation
- Evidence obligation: artifact name, checksum, install path, direct version
  output, wizard handoff
- Verification profile: installed-product WSL smoke plus release packaging
  contract
- Repair profile: checksum mismatch or unsupported OS fails closed

Outcome and trace:

- Outcome/truth warnings: do not claim Linux public install until artifact and
  script are tested from release-like URL/path
- Trace/debug fields: not REPL trace for installer; installed wizard smoke
  still requires trace in T929

Refactor scope:

- Allowed: focused installer/release-task extraction
- Forbidden: broad runtime setup rewrite

## Acceptance Criteria

- Linux runtime-bundled app-image tarball task exists and is documented as the
  first public Linux artifact, or a recorded proof explains why the ticket uses
  the BYO-JDK fallback instead.
- The chosen artifact lane has one canonical artifact name and checksum entry.
- Public Linux install script downloads a versioned artifact, verifies checksum,
  installs user-local, and prints/launches `talos setup wizard` only after Talos
  itself is installed.
- Unsupported OS/arch fails with clear message.
- Existing `tools/install-unix.sh` developer/source behavior remains intact or
  is clearly separated from the public installer.
- Installer does not run package managers, download models, or start servers
  without explicit wizard prompts.
- WSL inherited Windows PATH shadowing remains detected.
- Contract tests pin artifact names, checksum behavior, runtime-vs-BYO-JDK
  behavior, and no blind `curl | sh` assumptions.

## Tests / Evidence

Required deterministic regression:

- Unit test: public install packaging contract for Linux artifact name/checksum
  and selected runtime-vs-BYO-JDK lane.
- Unit test: install script refuses unsupported OS/arch.
- Unit test: install script verifies checksum before install.
- Integration/executor test: WSL installed smoke from the generated artifact.
- JSON e2e scenario: not applicable.
- Trace assertion: live wizard/REPL trace covered by T929.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

With installer/release-task changes:

```powershell
.\gradlew.bat check --no-daemon
wsl.exe -e bash -lc '<release-like install smoke command>'
```

## Known Risks

- Runtime-bundled Linux packaging may require platform-specific jpackage work.
- BYO-JDK public install is more fragile for first-time users.
- Download scripts can accidentally become unsafe if they skip checksum or
  encourage blind pipe-to-shell behavior.

## Known Follow-Ups

- deb/rpm/Homebrew/AppImage only after tarball/install-script beta is proven.

## Resolution

Implemented the first Linux public artifact lane as the primary
runtime-bundled target, not the BYO-JDK fallback.

Changed surfaces:

- `build.gradle.kts`
- `tools/install-talos.sh`
- `.github/workflows/release-staging.yml`
- `docs/public-installation.md`
- `README.md`
- `docs/user/installation.md`
- `docs/user/release-channels.md`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`
- `src/test/java/dev/talos/release/CiWorkflowContractTest.java`

The Gradle release lane now defines:

- `jpackageLinuxAppImage`
- `linuxReleaseAppTar`
- `copyLinuxReleaseBootstrap`
- `linuxReleaseChecksums`
- `linuxReleaseArtifacts`

Canonical Linux artifact set:

```text
talos-<version>-linux-x64-app.tar.gz
install-talos.sh
checksums.txt
```

The public Linux bootstrap:

- supports Linux x64 only;
- installs user-local under `~/.local/share/talos` by default;
- writes a `talos` shim under `~/.local/bin`;
- updates the selected shell profile idempotently;
- downloads from `ai21z/talos-assistant` GitHub Releases by default;
- supports local QA staging through `--artifact-file` and `--checksums-file`;
- verifies `checksums.txt` with `sha256sum` before extraction or install;
- verifies the installed `talos --version`;
- launches `talos setup wizard` only after Talos itself is installed, with
  `--no-wizard` available for deterministic smoke;
- does not run package managers, download model weights, download llama.cpp, or
  start servers outside the explicit setup wizard prompts.

The release staging workflow now also has a Linux QA staging job. It waits for
the Windows T929 automated gate/staging job, checks out the exact requested
SHA, verifies version and changelog identity, builds `linuxReleaseArtifacts`,
writes a Linux staging manifest, and uploads only
`qa-staging-talos-<version>-linux-x64`.

Verification:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --tests "dev.talos.release.CiWorkflowContractTest" --no-daemon
wsl.exe -e bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && bash -n tools/install-talos.sh'
wsl.exe -e bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli && chmod +x ./gradlew && ./gradlew linuxReleaseArtifacts --no-daemon'
wsl.exe -e bash -lc 'set -euo pipefail; cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli; audit_root="/mnt/c/Users/arisz/Projects/LOQ/loqj-cli/local/manual-testing/t931-linux-install-smoke"; rm -rf "$audit_root"; mkdir -p "$audit_root"; bash tools/install-talos.sh --artifact-file build/release/linux/talos-0.10.7-linux-x64-app.tar.gz --checksums-file build/release/linux/checksums.txt --install-root "$audit_root/install" --bin-dir "$audit_root/bin" --profile-file "$audit_root/profile" --force --no-wizard; "$audit_root/bin/talos" --version; "$audit_root/bin/talos" status --verbose | sed -n "1,18p"'
```

WSL smoke result:

- Linux x64 host had Java 21 and `/usr/bin/jpackage`.
- `linuxReleaseArtifacts` completed successfully.
- `install-talos.sh` installed from the staged tarball with checksum
  verification.
- Installed command reported `Talos 0.10.7` on Linux amd64.
- `status --verbose` started successfully.
- The script detected inherited PATH shadowing from the Windows-installed Talos
  path and printed a warning instead of silently trusting it.
