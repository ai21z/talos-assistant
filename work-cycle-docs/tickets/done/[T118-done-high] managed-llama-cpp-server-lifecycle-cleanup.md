# T118 - Managed llama.cpp Server Lifecycle Cleanup

Status: done
Severity: high
Area: llama.cpp backend / managed process lifecycle / audit isolation

## Problem

The T117 focused audit left repo-launched `llama-server.exe` processes running after the audit completed. I stopped 10 stale server processes manually after the run.

This was separate from T117's repair-target fix. It affected audit cleanliness, Windows resource usage, and confidence in managed backend behavior. A stale managed server can also contaminate later audits through port reuse, unexpected model state, or host memory pressure.

## Scope

- Ensure managed llama.cpp server processes started by Talos are stopped when Talos exits normally.
- Ensure managed server processes are stopped when startup fails after launch.
- Avoid killing unrelated user-launched llama.cpp processes.
- Add diagnostics for managed server start and stop lifecycle.
- Preserve existing managed server startup behavior for Qwen and GPT-OSS.

## Implementation

Implemented the cleanup at the ownership boundaries:

- `TalosBootstrap` now registers the context-owned `LlmClient` as a runtime-session close resource.
- `LlmClient.close()` is idempotent and exposes `isClosed()` for lifecycle tests.
- `LlamaCppServerManager.ensureStarted()` cleans up its owned process when readiness fails after process launch.
- `LlamaCppServerManager.close()` now requests graceful termination, waits briefly, then force-stops the same owned process if it remains alive.
- `ProcessBuilderLlamaCppProcessLauncher` exposes process wait and force-stop operations through the internal `LlamaCppProcess` seam.
- Managed server logs now include Talos-owned start/stop lifecycle diagnostics.

## Acceptance

- Tests cover managed process cleanup on normal shutdown.
- Tests cover cleanup when readiness fails after launch.
- Cleanup only targets the Talos-owned process handle.
- A focused Qwen/GPT-OSS lifecycle smoke left no repo-managed `llama-server.exe` processes behind.
- Logs clearly identify started and stopped managed server processes.

## Verification

- `.\gradlew.bat test --tests dev.talos.cli.repl.TalosBootstrapWiringTest --tests dev.talos.engine.llamacpp.LlamaCppServerManagerTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.engine.llamacpp.* --no-daemon`
- `.\gradlew.bat test --tests dev.talos.cli.repl.TalosBootstrapTest --tests dev.talos.cli.repl.TalosBootstrapWiringTest --tests dev.talos.cli.repl.TalosBootstrapReconcileTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.core.llm.LlmClientAsyncCloseTest --tests dev.talos.core.llm.LlmEngineResolverTest --no-daemon`
- `git diff --check`
- `.\gradlew.bat test --no-daemon`
- `.\gradlew.bat installDist --no-daemon`

All passed.

## Lifecycle Smoke

Smoke directory:

- `local/manual-testing/t118-managed-llama-cpp-lifecycle-smoke-20260504-012900/`

Models:

- `qwen2.5-coder:14b`
- `gpt-oss:20b`

Result:

- Pre-smoke `Get-Process -Name llama-server -ErrorAction SilentlyContinue` returned no rows.
- Post-smoke `Get-Process -Name llama-server -ErrorAction SilentlyContinue` returned no rows.
- Qwen log contains managed start and stopped diagnostics.
- GPT-OSS log contains managed start and stopped diagnostics.

## Non-Goals

- No model behavior tuning.
- No T61-style audit.
- No rewrite of the backend abstraction.
- No global process killer for user-managed servers.
