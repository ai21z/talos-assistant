# Quickstart

This page answers: "How do I get from a checkout to a usable Talos session?"

Jump to [Current Support](#current-support) if you need the current install status first.

## Current Support

The current reliable path is source/developer setup. Windows packaged install is
planned, but do not present it as available until a signed release artifact and
package manifest exist. Linux beta support is source/developer support from a
checkout, not a DEB/RPM/Homebrew/SDKMAN package.

## 1. Check Prerequisites

Use a Windows PowerShell session or a Linux shell for the current source setup.

Required for source setup:

- Java 21.
- Gradle through the repository wrapper.
- A local checkout of the Talos repository.
- On Ubuntu/WSL x64, the guided setup wizard can install the pinned CPU
  `llama.cpp` engine and download an accepted beta model after confirmation.
- On Windows or direct/expert Linux setup, provide a local `llama-server.exe`
  or `llama-server` when configuring managed llama.cpp.

Verify Java:

```powershell
java -version
```

Talos itself is built with Java 21.

## 2. Build The Distribution

From the repository root:

```powershell
.\gradlew.bat clean installDist
```

On Linux:

```bash
./gradlew clean installDist
```

If the wrapper is not executable:

```bash
chmod +x ./gradlew
```

This creates the development distribution under:

```text
build\install\talos
```

## 3. Install The Development Distribution

From the repository root:

```powershell
pwsh .\tools\install-windows.ps1 -Force
```

On Linux:

```bash
bash tools/install-unix.sh --force
```

Open a new terminal after PATH changes.

Verify:

```powershell
talos --version
```

## 4. Configure A Model

See [Model Setup](model-setup.md) for full details.

On Ubuntu/WSL x64, use the guided wizard:

```bash
talos setup wizard
```

The wizard asks before installing the pinned CPU `llama.cpp` engine, before
downloading Qwen or GPT-OSS model weights, before writing config, and before
running `talos doctor --start`.

Show model setup help:

```powershell
talos setup models
```

Write a managed llama.cpp profile after you have a valid local `llama-server`
binary. Windows paths usually end in `llama-server.exe`; Linux paths usually do
not.

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
```

or:

```powershell
talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write
```

Talos writes user configuration to:

```text
%USERPROFILE%\.talos\config.yaml
```

## 5. Check Runtime Status

From the workspace you want Talos to inspect:

```powershell
talos status --verbose
```

This reports workspace, index, backend, model, configuration path, user config
status, and engine health.

## 6. Start Talos

From the workspace directory:

```powershell
talos
```

Useful first prompts:

```text
What are the top-level files in this workspace?
Explain what you can do here without changing files.
Find files related to the failing test. Do not edit yet.
```

## 7. Maven Workspace Verification

Talos itself is built with Gradle, but Talos can verify Maven workspaces through
trusted workspace verification profiles. From a Maven project, declare a fixed
wrapper command, trust the declaration, then run it:

```text
/profiles configure maven_verify --exec ./mvnw --arg -B --arg --no-transfer-progress --arg verify --timeout-ms 600000 --expected-write target/
/profiles trust
/verify ws:maven_verify
```

This writes or updates `.talos/profiles.yaml` after approval, then pins the
current declaration by SHA-256. If the declaration changes later, run
`/profiles trust` again before `/verify` can execute it.

Use `./mvnw.cmd` instead of `./mvnw` when that is the wrapper present in a
Windows Maven workspace. Maven may resolve dependencies from the network and may
write to the local Maven cache unless the project is already configured for
offline use.

## 8. Exit

Inside the REPL:

```text
/q
```

Aliases include `/quit` and `/exit`.
