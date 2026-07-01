# Installation

This page answers: "What is the correct way to install Talos today, and what is
planned for public beta?"

## Current Support

Current supported user path:

- Source/developer setup from the repository on Windows or Linux.
- Windows remains the packaged installer target.
- Java 21 required for the current source setup.

Planned public beta path:

- Windows x64 package installation.
- Linux source/developer beta install from a checkout.
- Private Java runtime included with the installed app.
- Talos installed without model weights.
- Model setup remains a separate user action.

## Current Source Install

Build on Windows:

```powershell
.\gradlew.bat clean installDist
```

Install the Windows development distribution:

```powershell
pwsh .\tools\install-windows.ps1 -Force
```

Build on Linux:

```bash
./gradlew clean installDist
```

If needed:

```bash
chmod +x ./gradlew
```

Install the Linux source/developer distribution:

```bash
bash tools/install-unix.sh --force
```

Open a new terminal and verify:

```powershell
talos --version
talos status --verbose
```

## Planned Public Beta Install

The planned package name is:

```text
talos-cli
```

The planned package identity is:

```text
TalosProject.TalosCLI
```

The planned publisher is:

```text
Vissarion Zounarakis
```

Do not treat the public package path as live until release artifacts and package
manifests are published.

Linux beta support is source/developer support only. There is no current
DEB/RPM/Homebrew/SDKMAN/JBang package promise.

## What The Installer Will And Will Not Install

The planned public installer target is:

- Talos.
- A private Java runtime for Talos.
- A `talos` command shim on PATH.

The planned public installer target does not include:

- model weights
- a llama.cpp server executable
- a remote model account
- workspace indexes

After installation, model setup remains explicit:

```powershell
talos setup models
talos status --verbose
talos
```

## Development Installer Behavior

The current development installer:

- copies `build\install\talos` into `%LOCALAPPDATA%\Programs\talos`
- adds the installed `bin` directory to the current user's PATH
- requires the distribution to exist before it runs
- does not install Java

## Verify An Install

Use:

```powershell
talos --version
talos --help
talos status --verbose
```

If `talos` is not found after installing, open a new terminal first. User PATH
changes are not always visible in existing shells.
