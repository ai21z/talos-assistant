# T107 - Managed llama.cpp Readiness And Load-Failure Handling

Status: Open
Priority: High
Branch: v0.9.0-beta-dev
Source: T106 focused managed llama.cpp audit

## Evidence Summary

The T106 audit showed that Talos launches `llama-server.exe`, then immediately
sends chat requests before the server is ready. With `qwen2.5-vl-7b`, direct
llama.cpp probing returned HTTP 503 twice, then `/health` returned HTTP 200 and
chat worked. The Talos-managed run exposed a cold-start
`ConnectionFailed: Cannot connect to backend at http://127.0.0.1:18080`.

With `qwen3-coder-30b-a3b`, llama.cpp exited during model load because Vulkan
could not allocate enough device memory. Talos did not surface server stderr as
a structured setup/load failure.

## Goal

Make the managed llama.cpp backend wait for readiness and classify model-load
failures before chat requests are sent to the compat transport.

## Scope

- After launching managed `llama-server`, poll `/health` until ready, process
  exit, or timeout.
- Treat HTTP 503 during startup as loading, not as a final chat failure.
- Capture or redirect server stdout/stderr to a deterministic Talos log file.
- If the process exits before readiness, return a setup/load failure that
  includes a short stderr/log excerpt.
- Keep connect-only mode unchanged except for clearer health reporting.

## Acceptance Criteria

- Unit tests with a fake launcher/server prove `ensureStarted()` waits for
  health before returning.
- Tests cover startup HTTP 503 followed by HTTP 200.
- Tests cover process exit before readiness and include a stderr/log excerpt.
- A chat request is not sent before managed readiness.
- Status/diagnose report loading/setup failure clearly.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.engine.llamacpp.*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```
