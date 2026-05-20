# T277 - CI-Grade Artifact Canary Scan

Status: still-open - scanner/runtime sink tests exist; decide CI/check integration versus explicit release-gate task
Severity: high
Release gate: yes
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
