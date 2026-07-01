# [T33-done-high] Ticket: Implement Local Turn Trace Model V1
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T32 local trace design ticket

## Context

`TurnPolicyTrace` and `TurnAuditCapture` provide a compact foundation, but
Talos needs first-class local trace events for explainability, debugging, and
manual QA regression work.

## Goal

Implement local turn trace events using existing trace and audit seams.

## Non-Goals

- Do not upload traces.
- Do not store full sensitive payloads by default.
- Do not build a UI beyond existing CLI/debug surfaces.
- Do not implement permission or checkpointing in this ticket.

## Implementation Notes

The implementation should reuse:

- `TurnAuditCapture`
- `TurnPolicyTrace`
- `TurnResult`
- session/turn-log persistence seams
- deterministic scenario harness hooks

Add new classes only where they clarify the trace model. Avoid scattering trace
formatting through `AssistantTurnExecutor`.

## Acceptance Criteria

- Trace records task contract.
- Trace records phase transitions.
- Trace records tool surface.
- Trace records blocked reasons.
- Trace records approval required/granted/denied.
- Trace records tool results.
- Trace records verification result.
- Trace records outcome classification.
- Default redaction avoids full sensitive payloads.
- Debug/full capture is opt-in.
- Tests prove trace is local, deterministic, and redacted by default.
- Scenario runner can attach a trace id or trace summary.

## Tests / Evidence

Run focused tests for the new trace model and affected persistence/debug code,
then:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual Talos verification is required if CLI trace/debug output changes.

## Work-Test Cycle Notes

Use focused inner-loop tests while implementing. Run full `check` before
marking done because this touches runtime observability.

## Known Risks

- Trace schema churn can break future analysis. Version the schema or document
  compatibility expectations.
- Redaction mistakes can expose local secrets in debug artifacts.

## Current Code Read

- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/02-runtime-policy-ownership-map.md`
- `src/main/java/dev/talos/runtime/TurnAuditCapture.java`
- `src/main/java/dev/talos/runtime/TurnPolicyTrace.java`
- `src/main/java/dev/talos/runtime/TurnAudit.java`
- `src/main/java/dev/talos/runtime/TurnRecord.java`
- `src/main/java/dev/talos/runtime/TurnResult.java`
- `src/main/java/dev/talos/runtime/TurnTraceCapture.java`
- `src/main/java/dev/talos/runtime/TurnUserRequestCapture.java`
- `src/main/java/dev/talos/runtime/TurnTaskContractCapture.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/JsonTurnLogAppender.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/runtime/SessionStore.java`
- `src/main/java/dev/talos/runtime/NoOpSessionStore.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/repl/ReplRouter.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/SessionCommand.java`
- `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`
- `src/e2eTest/java/dev/talos/harness/ScenarioResult.java`
- `src/test/java/dev/talos/runtime/TurnTraceCaptureTest.java`
- `src/test/java/dev/talos/runtime/JsonTurnLogAppenderTest.java`
- `src/test/java/dev/talos/runtime/JsonSessionStoreTurnsTest.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`

## Planned Tests

- Add focused trace model/redaction/persistence tests first.
- Verify the new tests fail before implementation.
- Run focused tests for new trace model, persistence, and `/last trace`.
- Run `./gradlew.bat e2eTest --no-daemon`.
- Run `./gradlew.bat check --no-daemon`.

## Implementation Summary

- Added `dev.talos.runtime.trace` local trace v1 records and capture helpers:
  `LocalTurnTrace`, `TurnTraceEvent`, `TraceRedactionMode`,
  `TraceRedactor`, and `LocalTurnTraceCapture`.
- Attached redacted local traces to `TurnAudit` and persisted them as separate
  local artifacts through `SessionStore` / `JsonSessionStore`.
- Stored the trace id on `TurnRecord` so `/last trace` can load the richer
  local trace artifact while preserving existing turn logs.
- Recorded task contract, phase/tool surface, model response summary, tool
  attempts, approval events, tool results, verification, outcome, and warnings
  without storing full prompts, answers, or write/edit payloads by default.
- Extended the executor scenario harness to attach a local trace summary.
- Enriched `/last trace` with local trace id, schema, redaction mode, visible
  tools, event count, verification status/problems, and outcome.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

Initial red test:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceTest" --no-daemon
```

Result: FAIL as expected before implementation; missing trace API classes.

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceTest" --tests "dev.talos.runtime.JsonSessionStoreTraceTest" --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.runtime.trace.LocalTurnTraceTest" --tests "dev.talos.runtime.JsonSessionStoreTraceTest" --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.JsonTurnLogAppenderTest" --tests "dev.talos.runtime.JsonSessionStoreTraceTest" --no-daemon
```

Result: PASS.

Focused e2e:

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.readOnlyRepoQuestion" --no-daemon
```

Result: PASS.

Full deterministic e2e:

```powershell
./gradlew.bat e2eTest --no-daemon
```

Result: PASS.

Hard gate:

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS.

Installed manual build:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Result: PASS.

## Manual Talos Check Result

Command:

```powershell
@('/session clear','/debug trace','What files are in this folder?','/last trace','/q') |
  & 'C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat' 2>&1 |
  Tee-Object -FilePath '.\local\manual-testing\T33-output.txt'
```

Workspace:

`local/manual-workspaces/T33/`

Model:

`qwen2.5-coder:14b`

Prompt:

`What files are in this folder?`

Approval choice:

None; read-only turn.

Observed tools:

`talos.list_dir`, `talos.read_file`, `talos.retrieve`, `talos.grep`

Files changed:

No workspace files changed.

Output file:

`local/manual-testing/T33-output.txt`

Pass/fail:

PASS for T33 trace behavior.

Notes:

- `/last trace` showed a local trace id, schema `1`, redaction `DEFAULT`,
  task contract, visible tools, event count, verification, and outcome.
- The persisted trace artifact under
  `C:\Users\arisz\.talos\sessions\traces\<session-id>\` did not contain the
  raw hidden token, raw prompt, or raw assistant answer when searched
  (`RAW_MATCHES=0`).
- Non-blocking product follow-up: the live model over-inspected a file-listing
  prompt by reading/grepping `notes.md` and hit the tool-call iteration limit
  on a simple “what files are in this folder?” request. The trace redaction
  worked; the over-inspection belongs to later resource/permission policy work.

## Known Follow-Ups

- Resource policy should distinguish “list files” from “read file contents,”
  especially for secret/token-like files. This aligns with the upcoming
  permission and resource-policy milestone.
- Full debug trace capture remains a future explicit opt-in mode; T33 stores
  only default redacted local trace summaries.

## Commit

Pending: `T33: implement local turn trace model v1`
