# T118 - Managed llama.cpp Server Lifecycle Cleanup

Status: open
Severity: high
Area: llama.cpp backend / managed process lifecycle / audit isolation

## Problem

The T117 focused audit left repo-launched `llama-server.exe` processes running after the audit completed. I stopped 10 stale server processes manually after the run.

This is separate from T117's repair-target fix. It affects audit cleanliness, Windows resource usage, and confidence in managed backend behavior. A stale managed server can also contaminate later audits through port reuse, unexpected model state, or host memory pressure.

## Evidence

- Audit directory: `local/manual-testing/t117-static-repair-target-audit-20260504-002313/`
- After the audit ended, `Get-CimInstance Win32_Process -Filter "name = 'llama-server.exe'"` showed multiple repo-launched servers from `local/engines/llama-cpp`.
- Manual cleanup stopped 10 repo-managed `llama-server.exe` processes.

## Scope

- Ensure managed llama.cpp server processes started by Talos are stopped when Talos exits normally.
- Ensure managed server processes are stopped when an audit runner exits normally or fails.
- Avoid killing unrelated user-launched llama.cpp processes.
- Add diagnostics for stale managed servers that cannot be stopped.
- Preserve existing managed server startup behavior for Qwen and GPT-OSS.

## Acceptance

- Tests cover managed process cleanup on normal shutdown.
- Tests cover cleanup when the client path fails or exits early.
- Cleanup only targets Talos-managed processes, not arbitrary `llama-server.exe` instances.
- A focused audit or smoke run leaves no repo-managed `llama-server.exe` processes behind.
- Logs clearly identify started and stopped managed server processes.

## Non-Goals

- No model behavior tuning.
- No T61-style audit.
- No rewrite of the backend abstraction.
- No global process killer for user-managed servers.
