# Contributing to Talos

Talos is a local-first Java CLI workspace assistant. Changes should preserve
its trust discipline: inspect before acting, retrieve before guessing, ask
before writing, verify before claiming completion, and keep local evidence.

## Quick Checklist

- Use JDK 21 or newer.
- Use the Gradle wrapper from the repository root.
- Keep changes small, scoped, and evidence-backed.
- Add or update tests for behavior changes.
- Open a GitHub pull request with the relevant test output and docs updates.

## Local Build

On Windows:

```powershell
.\gradlew.bat --version
.\gradlew.bat check --no-daemon
.\gradlew.bat installDist
.\build\install\talos\bin\talos.bat --version
```

On Linux/macOS developer shells:

```bash
./gradlew --version
./gradlew check --no-daemon
./gradlew installDist
./build/install/talos/bin/talos --version
```

## Contribution Rules

- Do not add telemetry, remote model defaults, or hidden background execution.
- Do not weaken approval gates, protected-path handling, trace capture, or
  verification without explicit acceptance criteria and regression tests.
- Do not commit generated `build/`, local audit transcripts, prompt-debug
  artifacts, model caches, or private workspace data.
- Update user docs when public commands, setup flows, trust boundaries, or
  installation behavior changes.
- For release/candidate work, follow the repository work-test cycle under
  `work-cycle-docs/`.

## Pull Requests

Pull requests should include:

- the finding or issue being addressed;
- the exact tests or commands run;
- any known skipped/failed checks and why they are not part of the change;
- screenshots or terminal transcripts only when the change affects UI/output.
