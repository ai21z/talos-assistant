# Installation

Talos currently supports source setup from this repository and packaged 0.10.8 developer-beta artifacts from GitHub Releases.

Use this page to decide which install path applies to you. If you are contributing to Talos, use source setup. If you are installing the 0.10.8 developer beta, use the pinned GitHub Release commands below.

## Public beta install commands

Windows x64:

```powershell
iwr https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.ps1 -OutFile install-talos.ps1
powershell -ExecutionPolicy Bypass -File .\install-talos.ps1 -Version 0.10.8 -Force -AllowUnsigned
talos --version
```

The Windows 0.10.8 developer-beta assets are unsigned, so `-AllowUnsigned` is required for this release. Winget is not live yet. The planned package ID is `TalosLocal.Talos`, the searchable package name or moniker is `talos-cli`, and the publisher is Aris Zounarakis. Do not use `winget install --id TalosLocal.Talos -e` until `winget search --id TalosLocal.Talos -e` finds the package.

Ubuntu/WSL x64:

```bash
curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.sh | bash -s -- --version 0.10.8 --force
talos --version
```

The Linux installer starts `talos setup wizard` by default after Talos is installed. Add `--no-wizard` when you only want to replace the installed app.

The packaged beta artifacts include a bundled Java runtime. They install Talos only. They do not bundle a llama.cpp server or model weights.

After install, use the platform setup guide:

- [Windows setup](windows-setup.md)
- [Linux setup](linux-setup.md)

## Upgrade an existing install

To upgrade, rerun the installer with `--force` and the pinned version. "Pinned" means the exact release you want, for example `0.10.8` or `0.10.9`, not an open-ended latest channel.

For the beta, use the installer script attached to the same GitHub Release as the app artifact.

Windows beta example:

```powershell
iwr https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/install-talos.ps1 -OutFile install-talos.ps1
powershell -ExecutionPolicy Bypass -File .\install-talos.ps1 -Version 0.10.8 -Force -AllowUnsigned
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

Source setup builds the app from the checkout and installs a local distribution under `build/install/talos`. It is the right path for contributors, local QA, and anyone who wants to run the current checkout instead of a published release asset.

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

For concrete llama.cpp and model download links, use [Windows setup](windows-setup.md) or [Linux setup](linux-setup.md).

Then verify the model path:

```bash
talos doctor --start
```

Talos does not bundle a llama.cpp server or model weights. The setup flow either guides installation of the pinned Ubuntu/WSL lane or asks you to provide concrete local paths.

## Uninstall

Normal uninstall removes the Talos app and command shim. It keeps your local Talos data at `~/.talos` or `%USERPROFILE%\.talos`.

Purge removes both the app and Talos user data. Use purge only when you want to remove config, indices, logs, and Talos-owned caches too.

Windows:

```powershell
iwr https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/uninstall-talos.ps1 -OutFile uninstall-talos.ps1
powershell -ExecutionPolicy Bypass -File .\uninstall-talos.ps1
```

Windows purge:

```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall-talos.ps1 -Purge
```

Linux or WSL:

```bash
curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/uninstall-talos.sh | bash
```

Linux or WSL purge:

```bash
curl -fsSL https://github.com/ai21z/talos-assistant/releases/download/v0.10.8/uninstall-talos.sh | bash -s -- --purge
```
