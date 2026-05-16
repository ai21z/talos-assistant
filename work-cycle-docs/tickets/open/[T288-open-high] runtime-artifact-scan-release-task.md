# T288 - Runtime Artifact Scan Release Task

Status: open - implemented and used on focused capability audit roots
Severity: high
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Artifact canary scanning existed as tests and policy code, but maintainers needed a single release-facing command for completed live-audit artifact directories.

## Evidence from current code

This pass adds:

- `ArtifactCanaryScanCli`
- Gradle task `checkRuntimeArtifactCanaries`

## Evidence from tests/audits

`ArtifactCanaryScanTest` covers prompt-debug leaks, allowlisted fixtures, targeted manual-testing/manual-workspace scan roots, exact file/line reporting, and compiled class skipping.

The release task also passed against the latest two-model smoke artifact roots:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t267-live-audit-20260516-091319,local/manual-workspaces/t267-live-audit-20260516-091319" --no-daemon
```

Latest focused capability audit scan:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/capability-live-audit-20260516-195820,local/manual-workspaces/capability-live-audit-20260516-195820" "-PartifactScanAllowlist=<fixture allowlist>" --no-daemon
```

The task now requires explicit `-PartifactScanRoots=...`. A no-root invocation fails fast with a usage error so historical ignored manual-audit directories are not scanned accidentally.

## User impact

Maintainers can fail a release packet if prompt-debug/provider-body/session/trace/turn/log artifacts contain raw file-discovered canaries.

## Product risk

Without targeted scans, a live audit can produce unsafe durable artifacts while deterministic unit tests still pass.

## Runtime boundary affected

Prompt-debug, provider-body JSON, traces, sessions, turn JSONL, command-output artifacts, generated audit reports.

## Non-goals

- Do not commit raw live-audit artifacts.
- Do not scan compiled class files as text.

## Required behavior

Run after live audit:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<audit-id>,local/manual-workspaces/<audit-id>" --no-daemon
```

## Proposed implementation

Keep `ArtifactCanaryScanCli` as a small wrapper over `ArtifactCanaryScanner.scanRuntimeArtifacts(...)`.

## Tests

`./gradlew.bat test --tests "*ArtifactCanary*" --no-daemon`

## Acceptance criteria

- Task reports exact offending file and line.
- Task redacts the snippet in its own output.
- Task scans manual live-audit roots when targeted.

## Remaining blockers

- The task has run against the focused two-model capability audit.
- Private-document beta still needs the broader private-paperwork prompt bank plus targeted scan.
- Future v1 image/OCR audit artifacts will need their own targeted scan after image/OCR work resumes.

## Open questions

Should CI run a default broad scan plus require a manual targeted scan artifact for release branches?

## Related files

- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanCli.java`
- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java`
- `build.gradle.kts`
- `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java`
