# Installation

Talos currently supports source setup from this repository. Public packaged beta artifacts are staged before publication and become user-facing only after a GitHub Release exists.

Use this page to decide which install path applies to you. If you are checking the repository before a public beta, use the source setup. If you are testing a QA artifact, use the artifact instructions that came with that staging packet.

## Planned public install commands

Windows x64 package target:

```powershell
winget install --id TalosLocal.Talos -e
```

Ubuntu/WSL x64 tarball target:

```bash
curl -fsSL https://taloslocal.com/install.sh | sh
```

These commands are planned public paths. They go live only when release assets exist. The Windows package uses the `TalosLocal.Talos` package ID, the searchable package name or moniker is `talos-cli`, and the publisher is Aris Zounarakis.

## Upgrade an existing install

To upgrade, rerun the installer with `--force` and the pinned version. "Pinned" means the exact release you want, for example `0.10.8` or `0.10.9`, not an open-ended latest channel.

For the beta, use the installer script attached to the same GitHub Release as the app artifact.

Windows beta example:

```powershell
iwr https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.ps1 -OutFile install-talos.ps1
powershell -ExecutionPolicy Bypass -File .\install-talos.ps1 -Version 0.10.8 -Force
talos --version
```

Linux or WSL beta example:

```bash
curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.sh | bash -s -- --version 0.10.8 --force --no-wizard
talos --version
```

Use `--no-wizard` when you only want to replace the installed app. Omit it if you want the setup wizard to run again after the upgrade.

When Talos has a stable non-prerelease channel, `latest` can be used for the newest stable release. Beta users should keep using an explicit version because prereleases are not the same as the stable latest release.

## Source setup

Source setup builds the app from the checkout and installs a local distribution under `build/install/talos`. It is the right path for contributors, local QA, and anyone reading the repo before the first public release assets exist.

Windows:

```powershell
.\gradlew.bat clean installDist
.\build\install\talos\bin\talos.bat --version
```

Linux developer shell:

```bash
./gradlew clean installDist
./build/install/talos/bin/talos --version
```

The packaged beta artifacts include a bundled Java runtime. Source setup still requires Java 21+ because the repository build uses the Gradle wrapper and the local JDK.

## After Talos starts

Run setup for a local model:

```bash
talos setup wizard
```

On Windows, use:

```powershell
talos setup models
```

Then verify the model path:

```bash
talos doctor --start
```

Talos does not bundle a llama.cpp server or model weights. The setup flow either guides installation of the pinned Ubuntu/WSL lane or asks you to provide concrete local paths.
