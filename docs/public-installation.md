# Talos Public Installation Plan

Talos public beta installation has two lanes:

- Windows x64 packaged install is the public installer target.
- Linux source/developer beta install is supported from a checkout with Java 21,
  `./gradlew clean installDist`, and `bash tools/install-unix.sh --force`.

The Windows public install promise is:

```powershell
winget install --id TalosProject.TalosCLI -e
talos setup models
talos status --verbose
talos
```

This is the release target, not a claim that the package is already published.
Until a signed GitHub Release and winget manifest exist, Windows users should
follow the source/developer setup in `README.md`.

## Support Boundary

- Supported packaged public beta install: Windows x64.
- Supported Linux public beta path: source/developer install from a checkout.
- Windows public installer includes a bundled Java runtime.
- Linux source/developer setup requires a user-provided Java 21 runtime.
- Public installer installs Talos only.
- Public installer does not bundle a llama.cpp server or model weights.
- Model setup remains explicit: `talos setup wizard` on Ubuntu/WSL x64, or
  `talos setup models` for direct/expert managed llama.cpp config.
- No DEB/RPM/Homebrew/SDKMAN/JBang package is promised for this beta.
- macOS is not a public beta support claim until separate smoke evidence exists.
- `tools/install-unix.sh` is the Linux source/developer install helper, not a
  signed package-manager installer.

## Winget Identity

Use `talos-cli` as the public package name and moniker, but keep the exact
winget package ID in the normal `Publisher.Package` shape:

```yaml
PackageIdentifier: TalosProject.TalosCLI
PackageName: talos-cli
Publisher: Vissarion Zounarakis
Moniker: talos-cli
Commands:
  - talos
```

The friendly install can be `winget install talos-cli` once the package is
indexed. The exact install command remains:

```powershell
winget install --id TalosProject.TalosCLI -e
```

## Release Artifacts

GitHub Release is the canonical artifact host. Each public Windows release must
publish:

```text
Talos-<version>-windows-x64.msi
talos-<version>-windows-x64-app.zip
install-talos.ps1
checksums.txt
```

Optional later artifacts:

```text
Sigstore bundle
SBOM
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
`installDist` plus `tools/install-unix.sh` is the beta source/developer lane; it
is not a native package-manager channel.

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

```text
Talos-<version>-windows-x64.msi
talos-<version>-windows-x64-app.zip
install-talos.ps1
checksums.txt
```

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

The script downloads the versioned app-image ZIP from GitHub Releases, verifies
the SHA256 entry in `checksums.txt`, installs under
`%LOCALAPPDATA%\Programs\Talos`, writes a lowercase `talos.cmd` command shim,
and adds the shim directory to the current user's PATH.

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
