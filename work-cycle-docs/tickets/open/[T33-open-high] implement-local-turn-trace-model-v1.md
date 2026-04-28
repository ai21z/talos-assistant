# [T33-open-high] Ticket: Implement Local Turn Trace Model V1
Date: 2026-04-28
Priority: high
Status: open
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
