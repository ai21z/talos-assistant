# [T846-open-high] Beta Hardware And Retrieval Diagnostics

Status: open
Priority: high
Type: diagnostics
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Expose lightweight beta diagnostics for hardware, model setup, local host
policy, and retrieval/vector state without claiming GPU/VRAM support or running
expensive embedding probes by default.

## Scope

- Extend `talos doctor` and `/doctor` through the shared doctor probe list.
- Report OS, architecture, Java version, CPU count, JVM-visible max memory, and
  free space near the Talos home.
- Report RAG index path, existence, chunk count when available, vector setting,
  embedding provider/model/host locality, and current retrieval mode.
- Explicitly report that GPU/VRAM is not probed by Talos.
- Sanitize host text before rendering diagnostics.

## Non-Goals

- No GPU or VRAM probe.
- No model startup except existing `talos doctor --start` behavior.
- No embedding dimension network probe in the default doctor path.
- No retrieval ranking or indexing behavior change.
- No release requirement matrix.

## Acceptance Criteria

- `talos doctor` and `/doctor` share the new probes.
- Focused doctor tests cover hardware facts, BM25-only visibility, remote
  embedding host rejection, and secret-shaped host sanitization.
- Existing status rendering still includes chat model and embedding state.
- `check --no-daemon` and `wikiEvidenceCloseGate --rerun-tasks --no-daemon`
  pass before closeout.

## Implementation State

Status: implemented-awaiting-review

- Added `RuntimeEnvironmentProbe`.
- Added `RetrievalStateProbe`.
- Registered both probes in `DoctorEngine.probes(...)`.
- Updated user troubleshooting docs to point users at `talos doctor` for
  bounded hardware/retrieval diagnostics.

Focused red-first state:

- The doctor suite failed before implementation because the two probe classes
  did not exist.
- After implementation, `dev.talos.cli.doctor.*` passed.
