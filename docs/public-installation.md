# Talos Public Installation Plan

Talos public beta installation has two lanes:

- Windows x64 packaged install is the public installer target.
- Ubuntu/WSL x64 has a runtime-bundled Linux tarball target for public beta
  staging.
- Linux source/developer beta install remains supported from a checkout with
  Java 21, `./gradlew clean installDist`, and
  `bash tools/install-unix.sh --force`.

No public install path is live until GitHub Release assets exist. The commands
below document the target release shape and the source/developer fallback.

The Windows public install promise is:

```powershell
winget install --id TalosLocal.Talos -e
talos setup models
talos status --verbose
talos
```

This is the release target, not a claim that the package is already published.
Until a signed GitHub Release and winget manifest exist, Windows users should
follow the source/developer setup in `README.md`.

## Support Boundary

- Supported packaged public beta install target: Windows x64.
- Supported Ubuntu/WSL x64 public artifact target: runtime-bundled tarball.
- Supported Linux source/developer path: install from a checkout.
- Windows public installer includes a bundled Java runtime.
- Linux public tarball includes a runtime-bundled Talos app image.
- Linux source/developer setup requires a user-provided Java 21 runtime.
- Public installer installs Talos only.
- Public installer does not bundle a llama.cpp server or model weights.
- Model setup remains explicit: `talos setup wizard` on Ubuntu/WSL x64, or
  `talos setup models` for direct/expert managed llama.cpp config.
- no DEB/RPM/Homebrew/SDKMAN/JBang package is promised for this beta.
- macOS is not a public beta support claim until separate smoke evidence exists.
- `tools/install-unix.sh` is the Linux source/developer install helper, not a
  signed package-manager installer.

## Winget Identity

Use `talos-cli` as the public package name and moniker, but keep the exact
winget package ID in the normal `Publisher.Package` shape:

```yaml
PackageIdentifier: TalosLocal.Talos
PackageName: talos-cli
Publisher: Aris Zounarakis
Moniker: talos-cli
Commands:
  - talos
```

The friendly install can be `winget install talos-cli` once the package is
indexed. The exact install command remains:

```powershell
winget install --id TalosLocal.Talos -e
```

## Release Artifacts

GitHub Release is the canonical artifact host. Each public Windows release must
publish:

Windows x64:

```text
Talos-<version>-windows-x64.msi
talos-<version>-windows-x64-app.zip
install-talos.ps1
talos-<version>-sbom.cdx.json
checksums.txt
```

Ubuntu/WSL x64:

```text
talos-<version>-linux-x64-app.tar.gz
install-talos.sh
talos-<version>-sbom.cdx.json
checksums.txt
```

Optional later artifacts:

```text
Sigstore bundle
winget local-validation manifest evidence
```

## Release Build Requirements

The release builder must run on Windows x64 with:

- JDK 21 and `jpackage`.
- WiX installed for MSI/EXE packaging.
- Gradle 8.14 through `gradlew.bat`.
- Code-signing access for public artifacts.

The `jpackageApp` task builds the MSI path. The `jpackageAppImage` task builds a
bundled-runtime app image for the signed bootstrap fallback. `installDist`,
`distZip`, and `distTar` are development distribution outputs. On Linux,
`jpackageLinuxAppImage` builds the runtime-bundled app image and
`linuxReleaseArtifacts` stages the public tarball lane. `installDist` plus
`tools/install-unix.sh` remains the beta source/developer lane; it is not a
native package-manager channel.

## Release Build Commands

```powershell
.\gradlew.bat clean check --no-daemon
.\gradlew.bat windowsReleaseArtifacts --no-daemon
```

Expected output folder:

```text
build/release/windows/
```

Expected files:

Windows x64:

```text
Talos-<version>-windows-x64.msi
talos-<version>-windows-x64-app.zip
install-talos.ps1
talos-<version>-sbom.cdx.json
checksums.txt
```

Linux x64:

```text
talos-<version>-linux-x64-app.tar.gz
install-talos.sh
talos-<version>-sbom.cdx.json
checksums.txt
```

Linux public tarball staging command:

```bash
./gradlew linuxReleaseArtifacts --no-daemon
```

Linux public install command shape after release assets exist:

```bash
curl -fL -o install-talos.sh https://github.com/ai21z/talos-assistant/releases/download/v<version>/install-talos.sh
bash install-talos.sh --version <version>
```

The script downloads `talos-<version>-linux-x64-app.tar.gz`, verifies the
`checksums.txt` SHA-256 entry, installs user-local, verifies `talos --version`,
and then launches `talos setup wizard`. For QA against a staged local artifact,
use `--artifact-file`, `--checksums-file`, and `--no-wizard`.

Linux source/developer beta path:

```bash
./gradlew clean installDist --no-daemon
bash tools/install-unix.sh --force
talos --version
talos setup wizard
talos status --verbose
```

If the repository wrapper is not executable, run:

```bash
chmod +x ./gradlew
```

## CI And Branch Protection Policy

`main` is protected by GitHub branch protection. The protected public branch
requires these Talos CI checks before public main health can be treated as
green:

In short: main is protected; beta-dev is the integration lane.

```text
Gradle check (Java 21)
Linux command portability smoke (Java 21)
```

Verify the live protection rule with:

```powershell
gh api repos/ai21z/talos-assistant/branches/main/protection
```

`v0.9.0-beta-dev` is the beta-dev integration branch. Direct pushes to that
branch are allowed during beta hardening, but `.github/workflows/beta-dev-ci.yml`
runs Talos CI on every push to `main` and `v0.9.0-beta-dev`, and on pull
requests targeting either branch. The workflow intentionally does not keep
dead `codex/**`, `feature/**`, or old release-branch push patterns as release
policy.

## Release Staging Workflow

The first public artifact lane is staged by
`.github/workflows/release-staging.yml`. It is a manual `workflow_dispatch`
workflow with two required inputs:

```text
target_sha
version
```

The workflow checks out `target_sha`, verifies that `git rev-parse HEAD`
matches the requested commit, verifies `gradle.properties` contains
`talosVersion=<version>`, and verifies `CHANGELOG.md` has a matching version
heading. It then runs the automated portion of the T929 gate:

```powershell
git diff --check
.\gradlew.bat clean check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
.\gradlew.bat windowsReleaseArtifacts --no-daemon
```

The upload name is:

```text
qa-staging-talos-<version>-windows-x64
qa-staging-talos-<version>-linux-x64
```

That upload is a short-lived GitHub Actions workflow artifact, not a GitHub Release asset.
It is QA input only: it is not tagged, not signed as a public release, not
winget-linked, and not a public install promise.

No draft GitHub Release asset may be created before the T929 QA packet passes.
There is intentionally no publication workflow in this staging step. A future
publication workflow must require an explicit QA packet reference satisfying
T929 before it can create a draft or prerelease GitHub Release.
No tag, push, pull-request, or release event may publish public artifacts.

## Checksums, SBOMs, And Attestations

Checksums prove that downloaded bytes match the published checksum manifest.
They do not prove the files behave correctly, were tested, are vulnerability
free, or are safe to run in a particular workspace.

The SBOM is a CycloneDX dependency inventory for the Talos runtime dependency
graph staged with each OS artifact set. It helps reviewers and downstream
users inspect what runtime libraries were packaged. It is not a vulnerability
scan, license audit, malware scan, behavioral QA result, or model-safety
result.

Artifact attestations bind staged files to a GitHub Actions workflow run,
repository, source commit, and subject digest when verified against the
expected repository, signer workflow, and candidate SHA. They help prove where
and how the staged artifact was built. The workflow still produces the
predicate, so predicate fields are not a substitute for a trusted build policy.
They do not prove the installer was manually smoked, the model path works, the
two-model audit passed, or that Talos made truthful runtime claims.

None of these metadata artifacts prove Talos behavior is correct. Behavioral
release readiness remains owned by T929: full automated gates, manual PTY
evidence, two-model large-scale live audit evidence, runtime artifact canary
scans, and explicit exclusions before public release assets are created.

Online provenance verification after release-staging artifacts exist:

```powershell
gh attestation verify Talos-<version>-windows-x64.msi `
  --repo ai21z/talos-assistant `
  --signer-workflow ai21z/talos-assistant/.github/workflows/release-staging.yml `
  --source-digest <candidate-sha>

gh attestation verify Talos-<version>-windows-x64.msi `
  --repo ai21z/talos-assistant `
  --signer-workflow ai21z/talos-assistant/.github/workflows/release-staging.yml `
  --source-digest <candidate-sha> `
  --predicate-type https://cyclonedx.org/bom
```

Linux uses the same repository and signer-workflow checks against
`talos-<version>-linux-x64-app.tar.gz`.

## Signing And Checksum Rules

Public Windows installers must be signed. The bootstrap script uses
`Get-AuthenticodeSignature` and refuses unsigned scripts unless the caller passes
`-AllowUnsigned` for local development. Release assets are verified with
`Get-FileHash` against `checksums.txt`.

Do not publish a public download flow that asks users to pipe remote text into a
PowerShell interpreter.

## Bootstrap Fallback

Before or alongside winget, users may install from a signed GitHub Release
bootstrap:

```powershell
.\install-talos.ps1
```

The Windows script downloads the versioned app-image ZIP from GitHub Releases, verifies
the SHA256 entry in `checksums.txt`, installs under
`%LOCALAPPDATA%\Programs\Talos`, writes a lowercase `talos.cmd` command shim,
adds the shim directory to the current user's PATH, and broadcasts the Windows
environment change. Open a new PowerShell window if the current shell does not
resolve `talos` immediately.

The Linux script downloads the versioned runtime-bundled tarball from GitHub
Releases, verifies the SHA256 entry in `checksums.txt`, installs under
`~/.local/share/talos`, writes a lowercase `talos` command shim under
`~/.local/bin`, updates the selected shell profile, verifies `talos --version`,
and then starts `talos setup wizard` unless `--no-wizard` is used for QA.

## Model Setup

The installer does not configure models. On Ubuntu/WSL x64 source/developer
installs, the guided path is:

```bash
talos setup wizard
```

The wizard asks before installing the pinned CPU `llama.cpp` engine, asks before
downloading an accepted beta GGUF, asks before writing config, and asks before
running `talos doctor --start`.

For Windows or direct/expert setup, users must provide a compatible local
`llama-server` binary (`llama-server.exe` on Windows, `llama-server` on Linux)
and then run one of the setup commands:

```powershell
talos setup models
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

Talos writes configuration to:

```text
%USERPROFILE%\.talos\config.yaml
```

Managed Hugging Face model cache location:

```text
%USERPROFILE%\.talos\models\huggingface
```

## Verification Gate

Build and package:

```powershell
.\gradlew.bat clean check --no-daemon
.\gradlew.bat windowsReleaseArtifacts --no-daemon
Get-FileHash build\release\windows\*.msi -Algorithm SHA256
Get-FileHash build\release\windows\*.zip -Algorithm SHA256
Get-AuthenticodeSignature build\release\windows\*.msi
Get-AuthenticodeSignature build\release\windows\install-talos.ps1
```

Installed product:

```powershell
talos --version
talos --help
talos status --verbose
talos
```

Model setup:

Ubuntu/WSL x64 guided path:

```bash
talos setup wizard
talos status --verbose
```

Direct/expert setup:

```powershell
talos setup models
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
talos status --verbose
```

On Ubuntu/WSL x64, the wizard should be validated with one accepted profile,
config write, and `talos doctor --start`. On direct Linux setup, use the real
path to the local `llama-server` binary instead of the Windows `.exe` example
path.

Release validation must also verify a fresh PowerShell session sees `talos` on
PATH, uninstall removes installed program files, and `%USERPROFILE%\.talos`
survives unless a purge operation is explicitly requested.

Linux source/developer validation must verify a fresh shell sees `talos` on
PATH, `talos --version` and `talos status --verbose` start successfully, and
the built-in Gradle command profiles plan against `./gradlew`, not
`.\\gradlew.bat`. The Ubuntu/WSL guided setup gate must also verify one
accepted beta model lane through `talos setup wizard` and `talos doctor
--start`.

## Evidence Anchors

- Oracle `jpackage` documentation:
  <https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html>
- OpenJDK JEP 392 notes that Windows MSI/EXE packaging requires WiX and has no
  built-in auto-update:
  <https://openjdk.org/jeps/392>
- Gradle Distribution Plugin:
  <https://docs.gradle.org/current/userguide/distribution_plugin.html>
- winget manifest documentation:
  <https://learn.microsoft.com/en-us/windows/package-manager/package/manifest>
- GitHub Releases documentation:
  <https://docs.github.com/en/repositories/releasing-projects-on-github/about-releases>
- Microsoft code-signing options:
  <https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options>
- llama.cpp:
  <https://github.com/ggml-org/llama.cpp>
- Hugging Face cache documentation:
  <https://huggingface.co/docs/hub/local-cache>
