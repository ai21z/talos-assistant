# Troubleshooting

This page answers: "What do I check when Talos does not work?"

## Start With Status

Run:

```powershell
talos status --verbose
talos doctor
```

Inside the REPL:

```text
/status --verbose
```

For top-level `talos status --verbose`, check:

- workspace
- index directory
- backend
- model
- engine host
- health
- config path
- user config status

For REPL `/status --verbose`, check active mode, model, index path, config,
limits, cache, document extraction, and XML compatibility status.

`talos doctor` and `/doctor` run the same fast preflight probes. They report
bounded local hardware/runtime facts, chat backend and model setup, RAG index
state, vector setting, embedding provider/model/host locality, and whether
retrieval is currently BM25-only or hybrid-if-embeddings-work. Doctor does not
probe GPU/VRAM and says so explicitly.

## `talos` Is Not Found

Try:

1. Open a new PowerShell window.
2. Run `talos --version`.
3. Check that the install `bin` directory is on the user PATH.
4. If using source setup, rerun:

```powershell
.\gradlew.bat clean installDist
pwsh .\tools\install-windows.ps1 -Force
```

## Wrong Or Missing Java

For source setup, verify Java 21:

```powershell
java -version
```

The planned public installer target includes a private runtime, but the source
setup uses the Java available to the build.

## Model Config Missing

Show setup help:

```powershell
talos setup models
```

Write a model profile after locating the local `llama-server` binary:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write
```

If you have not downloaded a server binary yet, use
[Where to get `llama-server`](model-setup.md#where-to-get-llama-server).

If config already exists, rerun with `--force` only after reviewing the current
file:

```text
%USERPROFILE%\.talos\config.yaml
```

## `llama-server` Path Is Invalid

The setup command requires `--server-path` to point to a regular file.

Fix the path and rerun setup.

## Config Parse Failed

Run:

```powershell
talos status --verbose
```

It reports the user config path and parse error.

For Windows paths in YAML, prefer forward slashes:

```yaml
server_path: "C:/Users/me/talos/llama-server.exe"
```

or single quotes:

```yaml
server_path: 'C:\Users\me\talos\llama-server.exe'
```

## Index Is Not Ready

Inside the REPL:

```text
/reindex
/reindex --stats
/reindex --prune [days]
```

Then check:

```text
/status
```

## RAG Diagnosis Fails

`talos diagnose` requires a question:

```powershell
talos diagnose --mode rag -q "What files define the CLI commands?"
```

Use it when retrieval returns no useful snippets, a RAG answer is empty, or
status suggests configuration problems.

## File Cannot Be Read Or Summarized

Check [File Support](file-support.md).

A correct result reports unsupported, encrypted, corrupt, image-only, or
disabled document extraction states instead of pretending it read the file.

## Approval Was Denied

Denied actions are expected to leave the workspace unchanged. Retry the request
and approve only if the action, target, and risk are correct.

## Command Was Rejected

Talos uses command profiles. The current model-callable command surface accepts
Gradle verification profiles, not arbitrary shell commands. Unknown profiles and
workspace-escaping working directories are rejected.

Start with:

```text
/status --verbose
/last trace
```

## Evidence For A Failed Turn

Inside the REPL:

```text
/last trace
```

For deeper debugging, maintainer/audit commands exist, but normal users start
with `/status --verbose` and `/last trace`.
