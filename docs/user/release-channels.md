# Release Channels

This page answers: "What is available now, and what is planned for public beta?"

## Current Status

Current version source:

```text
gradle.properties -> talosVersion
```

Current Java baseline:

```text
Java 21
```

Current public documentation treats source/developer setup as the reliable path
until release artifacts exist.

## Planned Public Beta

The planned packaged public beta install targets are Windows x64 and
Ubuntu/WSL x64.

The Linux source/developer beta path is a checkout-based install:

```bash
./gradlew clean installDist
bash tools/install-unix.sh --force
```

That is not a DEB/RPM/Homebrew/SDKMAN/JBang package promise.

The first Linux public beta artifact shape is a runtime-bundled tarball:

```text
talos-<version>-linux-x64-app.tar.gz
install-talos.sh
checksums.txt
```

The planned package identity is:

```text
TalosProject.TalosCLI
```

The planned public package name and moniker are:

```text
talos-cli
```

The planned Windows public installer target includes Talos and a private Java
runtime. Model weights and the llama.cpp server remain separate user-controlled
setup steps on both Windows and Linux.

## Release Artifacts

The planned Windows release artifacts are:

```text
Talos-<version>-windows-x64.msi
talos-<version>-windows-x64-app.zip
install-talos.ps1
checksums.txt
```

The planned Ubuntu/WSL x64 release artifacts are:

```text
talos-<version>-linux-x64-app.tar.gz
install-talos.sh
checksums.txt
```

Until those artifacts exist in a signed release, do not present public package
installation as available.

Linux native package-manager evidence is out of scope for this beta tarball
lane.

## Verification Expectations

A release candidate verification pass includes:

```powershell
talos --version
talos --help
talos status --verbose
talos
```

Model setup verification includes:

```powershell
talos setup models
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
talos status --verbose
```

## Changelog

`CHANGELOG.md` remains the release ledger. User-facing release notes are shorter
than internal ticket history and describe user-visible changes, fixes, known
limits, and upgrade notes.

## Other Platforms

macOS and other operating systems are source/developer-only experiments until
packaging, install, smoke testing, and support boundaries are completed for
those targets.
