# T285 - Artifact Scanner Surface Coverage

Status: open
Severity: high
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Artifact canary scanning must explicitly cover runtime artifact directories, not only a broad scan that skips noisy generated directories.

## Evidence from current code

This pass adds `ArtifactCanaryScanner.scanRuntimeArtifacts(...)`, which uses narrower skip behavior for targeted runtime artifact directories.

## Evidence from tests/audits

`ArtifactCanaryScanTest` now checks prompt-debug, provider-body, session, trace, turn JSONL, command-output artifacts, generated reports, exact file/line reporting, and compiled-class skipping.

## User impact

Users need confidence that prompt-debug, traces, logs, and sessions do not persist file-discovered canaries.

## Product risk

Artifact leaks can persist sensitive content even when final answers look safe.

## Runtime boundary affected

Prompt-debug output, provider-body JSON, local traces, sessions, turn JSONL, command-output capture, RAG/index artifacts, generated reports.

## Non-goals

- Scanning compiled class files as text.
- Committing raw live-audit canary artifacts.

## Required behavior

- Keep deterministic scanner unit coverage in `check`.
- Require explicit live-audit roots for targeted runtime artifact scans.
- Avoid blanket report-directory skipping for generated runtime artifacts.
- Distinguish fixture/source canaries, user-supplied query canaries, and file-discovered canaries.

## Proposed implementation

Preserve the broad scan for current generated output and add targeted scans wherever tests create runtime artifact directories.

## Tests

- `ArtifactCanaryScanTest`

## Acceptance criteria

- Scanner prints exact offending file and line.
- Runtime artifact directories are scanned unless explicitly allowlisted.
- No raw file-discovered canary appears in generated runtime artifacts during focused tests.

## Remaining blockers

- Keep adding targeted scan roots as new runtime artifact surfaces are introduced.
- Private-document beta still needs a larger private-paperwork live audit and targeted scan.

## Open questions

- Should release scripts run a separate scan against `local/manual-testing/<audit-id>` after live audits?

## Related files

- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java`
- `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java`

## 2026-05-15 final pre-beta update

Added `ArtifactCanaryScanCli` and Gradle task `checkRuntimeArtifactCanaries` for targeted release scans of live-audit artifact directories:

```powershell
./gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/<audit-id>,local/manual-workspaces/<audit-id>" --no-daemon
```

Follow-up ticket: T288.

## 2026-05-16 update

`checkRuntimeArtifactCanaries` now requires explicit `-PartifactScanRoots=...`. Running it without roots fails fast with a usage error instead of scanning every historical ignored `local/manual-testing` and `local/manual-workspaces` tree. Targeted scan passed on beta-core audit `capability-live-audit-20260516-195820`.
