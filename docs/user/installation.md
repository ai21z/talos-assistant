# Installation

This page answers: "What is the correct way to install Talos today, and what is
planned for public beta?"

## Current Support

Current supported user path:

- Source/developer setup from the repository on Windows or Linux.
- Windows remains the packaged installer target.
- Java 21 required for the current source setup.
- Ubuntu/WSL x64 has a guided post-install setup wizard for the local
  llama.cpp engine, accepted beta model downloads, config writing, and doctor
  verification.

Planned public beta path:

- Windows x64 package installation.
- Ubuntu/WSL x64 runtime-bundled Linux tarball install.
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

On Ubuntu/WSL x64, run the guided model setup:

```bash
talos setup wizard
```

The wizard is explicit at every side-effect boundary. It asks before installing
the pinned CPU `llama.cpp` engine, asks before downloading Qwen or GPT-OSS model
weights, asks before writing `~/.talos/config.yaml`, and asks before running
`talos doctor --start`.

## Planned Public Beta Install

The planned package name is:

```text
talos-cli
```

The planned package identity is:

```text
TalosLocal.Talos
```

The planned publisher is:

```text
Aris Zounarakis
```

Do not treat the public package path as live until release artifacts and package
manifests are published.

Windows public beta is signed-only. `-AllowUnsigned` is local development/manual QA only, not a public beta install path.

Ubuntu/WSL x64 public artifact support is scoped to the runtime-bundled
`talos-<version>-linux-x64-app.tar.gz` tarball and `install-talos.sh` on
Ubuntu/WSL x64 once release assets exist. Linux source/developer support from a
checkout remains available. There is no current DEB/RPM/Homebrew/SDKMAN/JBang
package promise.

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

Ubuntu/WSL x64 guided path:

```bash
talos setup wizard
talos status --verbose
talos
```

Linux public tarball install shape after release assets exist:

```bash
curl -fL -o install-talos.sh https://github.com/ai21z/talos-assistant/releases/download/v<version>/install-talos.sh
bash install-talos.sh --version <version>
```

Direct/expert path when you already have a compatible local `llama-server`
binary. If you need the binary first, see
[Where to get `llama-server`](model-setup.md#where-to-get-llama-server):

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

The Unix source/developer installer:

- copies `build/install/talos` into `~/.local/talos`
- detects Java 21 before launching Talos
- updates the selected shell profile without duplicating PATH entries
- verifies the direct installed Linux binary
- does not install system packages, llama.cpp, model weights, or config

## Skip, Offline, And Rerun Behavior

`talos setup wizard` is safe to stop or decline:

- Answering `n` to the pinned engine install skips model setup and writes no
  config.
- Answering `n` to model download writes no config and starts no model.
- Answering `n` to config write leaves the generated config unapplied.
- Answering `n` to `talos doctor --start` prints the manual command to run
  later.

Offline or restricted-network users can skip the wizard download and use the
direct path instead. Use a server binary you already trust or the pinned
official release guidance in
[Model Setup](model-setup.md#where-to-get-llama-server):

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --model-path D:/models/qwen.gguf --write
```

On Linux, use a Linux `llama-server` path and a Linux-accessible GGUF path.

Reruns are intended to be idempotent:

- the Unix installer does not duplicate its PATH entry;
- the wizard reuses an existing pinned engine;
- the wizard verifies an existing downloaded model checksum before reuse;
- config overwrites require confirmation and create a backup first.

If a download fails or a checksum mismatch is denied, Talos does not promote the
partial file to the final model path.

## Uninstall Development Installs

Linux source/developer install:

```bash
rm -rf ~/.local/talos
```

Then remove the Talos PATH block from the profile file you used during install
such as `~/.bashrc`, `~/.zshrc`, `~/.profile`, or the explicit
`--profile-file`.

Windows development install:

```powershell
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Programs\talos"
```

Remove the Talos user PATH entry from Windows Environment Variables if you no
longer want `talos` on PATH.

Model/config data under `~/.talos` or `%USERPROFILE%\.talos` is user data. It
survives uninstall unless you deliberately remove it.

## Verify An Install

Use:

```powershell
talos --version
talos --help
talos status --verbose
```

If `talos` is not found after installing, open a new terminal first. User PATH
changes are not always visible in existing shells.
