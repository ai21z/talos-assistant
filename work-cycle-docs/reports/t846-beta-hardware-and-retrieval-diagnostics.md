# T846 Beta Hardware And Retrieval Diagnostics

Status: implemented-awaiting-review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Created: 2026-06-21

## Summary

T846 adds bounded diagnostics to the existing doctor probe surface. It does not
change retrieval behavior, does not claim GPU/VRAM support, and does not probe
embedding dimensions by default.

## Implementation

- `RuntimeEnvironmentProbe` reports OS, architecture, Java version, CPU count,
  JVM-visible max memory, free disk near the Talos home, and the explicit
  `GPU/VRAM not probed by Talos` boundary.
- `RetrievalStateProbe` reports index path, index existence, chunk count when
  available, vector setting, embedding provider/model, sanitized embedding
  host, host locality, and the retrieval mode:
  - `BM25-only (vectors disabled)`
  - `BM25-only (embedding provider disabled)`
  - `BM25-only fallback likely (embedding host rejected)`
  - `hybrid if embedding probe succeeds`
- `DoctorEngine.probes(...)` now runs the probes in this order:
  `config`, `runtime-env`, `engine-profile`, `engine-files`, `server`,
  `retrieval`, `index-writable`, `home-writable`.

## Boundaries

- Doctor does not start or query an embedding endpoint to measure dimensions.
- Doctor does not detect GPU or VRAM.
- Remote embedding hosts are identified from config and reported as rejected
  unless `embed.allow_remote=true`.
- Diagnostic host text is sanitized before rendering.

## Verification

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.doctor.*" --no-daemon
```

Result: passed after implementation.

Broader gates are required before closeout:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```
