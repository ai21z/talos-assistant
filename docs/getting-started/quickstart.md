# Quickstart

Use this path for a fresh source checkout or installed beta candidate. It gives you a small workspace, verifies that the installed command can start, checks the model path, and performs one read-only turn before any write test.

If Talos is already installed and configured, jump to the [read-only smoke](#read-only-smoke) or the [write test](#write-test).

## Verify the command

```bash
talos --version
```

## Create a test workspace

```bash
mkdir -p ~/talos-smoke
cd ~/talos-smoke
cat > README.md <<'EOF'
# Smoke

This workspace is for Talos testing.
EOF
```

## Configure a model

```bash
talos setup wizard
```

On Windows, configure model paths with:

```powershell
talos setup models
```

For the shortest platform-specific path, use [Windows setup](windows-setup.md) or [Linux setup](linux-setup.md).

## Run the doctor smoke

```bash
talos doctor --start
```

Expected result: doctor should report configuration, runtime environment, engine files, server smoke, retrieval state, and writable local directories. A failed server or model smoke means the REPL is not ready for model-backed testing yet.

## Start the REPL

```bash
talos
```

## Read-only smoke

Inside the REPL:

```text
/status --verbose
/mode ask
What is in this workspace?
/last trace
```

## Write test

For a write test, switch to Agent mode and expect an approval prompt before mutation:

```text
/mode agent
Append one sentence to README.md saying this is a Talos smoke workspace.
/last trace
```

Review the changed file yourself. Talos final answers are useful summaries, not the primary proof.

If the write lane is denied, partial, or failed, keep the result as evidence instead of retrying immediately. Inspect `/last trace`, the final file, and any approval prompt so you know whether the failure was configuration, model behavior, policy, or verification.
