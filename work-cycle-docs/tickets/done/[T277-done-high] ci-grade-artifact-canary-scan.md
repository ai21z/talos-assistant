# T277 - CI-Grade Artifact Canary Scan

Status: done - generated-artifact canary scan is now part of `check`; manual/live audit roots remain explicit release-audit scan inputs
Severity: high
Release gate: closed for generated local verification artifacts
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-17
Owner: unassigned

## Problem

Manual artifact scanning is not a release gate. Talos needs deterministic tests/tasks that fail if raw canaries appear in generated artifacts.

## Evidence from current code

- `ArtifactCanaryScanner` scans text-like artifact files for explicit raw canaries and T275 secret values.
- `ArtifactCanaryScanner.scanRuntimeArtifacts(...)` applies narrower skip behavior for targeted runtime artifact directories.
- `ArtifactCanaryScanner` and `ProtectedContentPolicy` now share a deterministic private-document fact canary class for ordinary private fact fixtures.
- `ArtifactCanaryScanTest` exercises detection, allowlisting, current generated roots, and targeted runtime artifact dirs.

## Evidence from tests/audits

- Focused artifact scan test passed in this pass.
- Targeted tests cover prompt-debug, provider body, session, trace, turn JSONL, command-output artifacts, generated reports, exact file/line reporting, and compiled-class skipping.
- Additional runtime sink tests cover prompt-debug/provider-body formatting, session snapshots, turn JSONL, local trace JSON, memory persistence, and log/trace helper redaction for configured ordinary private-document fact canaries.
- Post-clean targeted scans passed for `build/reports,build/test-results` and `work-cycle-docs/reports,work-cycle-docs/tickets`.

## User impact

Without CI-grade scanning, sensitive values may persist in prompt-debug/provider-body/session/log artifacts unnoticed.

## Product risk

High for beta quality; P0 if private/sensitive folders are positioned as supported.

## Runtime boundary affected

Prompt-debug markdown, provider-body JSON, traces, sessions, turn JSONL, logs, generated reports.

## Non-goals

- Do not scan compiled class files or binary blobs.
- Do not treat fixture/source files as runtime leaks.

## Required behavior

The scan runs automatically during `check`, prints exact offending files/lines, and supports explicit fixture allowlists.

## Proposed implementation

JUnit path exists. Add a dedicated Gradle task if release engineering wants a named gate.

## Tests

- `artifact_scan_detects_disallowed_file_discovered_canary`
- `artifact_scan_allows_explicit_allowlisted_files`
- `artifact_canary_scan_current_generated_artifacts_passes`
- `artifact_scan_checks_prompt_debug_dir`
- `artifact_scan_checks_provider_body_dir`
- `artifact_scan_checks_session_dir`
- `artifact_scan_checks_trace_dir`
- `artifact_scan_checks_turn_jsonl_dir`
- `artifact_scan_checks_command_output_artifacts`
- `artifact_scan_does_not_hide_generated_reports_unless_allowlisted`
- `artifact_scan_reports_exact_file_and_line`
- `artifact_scan_ignores_compiled_classes_without_skipping_text_reports`
- `artifact_scan_detects_private_document_fact_canary_and_redacts_snippet`
- `PromptDebugInspectorPrivateDocumentTest`
- `JsonSessionStoreTest` private-document fact persistence cases
- `JsonTurnLogAppenderTest` private-document fact persistence case
- `MemoryUpdateListenerTest` private-document fact persistence case
- `TraceRedactorTest.redactsPrivateDocumentFactCanaries`

## Acceptance criteria

- `./gradlew.bat check` runs the scan.
- No disallowed generated artifact contains raw canaries.

## Rollback / migration notes

Old ignored manual audit folders are not treated as current CI artifacts by default.

## Open questions

- Should release audits scan ignored `local/manual-testing` folders separately as a manual gate?
- Should the release gate require a generated manifest of scanned roots plus allowlist entries?

## Related files

- `src/main/java/dev/talos/runtime/policy/ArtifactCanaryScanner.java`
- `src/test/java/dev/talos/runtime/policy/ArtifactCanaryScanTest.java`

## 2026-05-20 closure update

Implemented CI-grade generated-artifact scan wiring:

- Added `checkGeneratedArtifactCanaries` in `build.gradle.kts`.
- The task scans `build/reports` and `build/test-results` in runtime-artifact
  mode after unit/e2e/report generation.
- `tasks.check` now depends on `checkGeneratedArtifactCanaries`, so the normal
  local verification gate runs the canary scan automatically.
- Kept `checkRuntimeArtifactCanaries` as the explicit manual/live audit root
  scanner requiring `-PartifactScanRoots=...`; this avoids accidentally scanning
  stale ignored manual-audit artifacts during every local `check`.
- Added `ArtifactCanaryBuildGateTest` to prevent the Gradle check wiring from
  silently drifting.

Fresh evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.build.ArtifactCanaryBuildGateTest.checkRunsGeneratedArtifactCanaryScan" --no-daemon
.\gradlew.bat checkGeneratedArtifactCanaries --no-daemon
.\gradlew.bat check --no-daemon
```

The final `check` output included:

```text
> Task :checkGeneratedArtifactCanaries
Artifact canary scan passed. Roots scanned: [C:\Users\arisz\Projects\LOQ\loqj-cli\build\reports, C:\Users\arisz\Projects\LOQ\loqj-cli\build\test-results]
> Task :check
BUILD SUCCESSFUL
```
