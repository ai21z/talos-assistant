# Quickstart

This page answers: "How do I get from a checkout to a usable Talos session?"

Jump to [Current Support](#current-support) if you need the current install status first.

## Current Support

The current reliable path is source/developer setup. A public package-manager
installer is planned, but do not present it as available until a signed release
artifact and package manifest exist.

## 1. Check Prerequisites

Use a Windows PowerShell session for the current beta path.

Required for source setup:

- Java 21.
- Gradle through the repository wrapper.
- A local checkout of the Talos repository.
- A local `llama-server.exe` when configuring managed llama.cpp.

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

This creates the development distribution under:

```text
build\install\talos
```

## 3. Install The Development Distribution

From the repository root:

```powershell
pwsh .\tools\install-windows.ps1 -Force
```

Open a new PowerShell window after PATH changes.

Verify:

```powershell
talos --version
```

## 4. Configure A Model

See [Model Setup](model-setup.md) for full details.

Show model setup help:

```powershell
talos setup models
```

Write a managed llama.cpp profile after you have a valid `llama-server.exe`:

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

## 7. Exit

Inside the REPL:

```text
/q
```

Aliases include `/quit` and `/exit`.
