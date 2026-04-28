# [T32-open-high] Ticket: Design Local Turn Trace Model V1
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`

## Context

Talos currently records compact policy data through `TurnPolicyTrace` and tool
activity through `TurnAuditCapture`. This is useful but not yet a first-class
local trace model that can explain a turn end to end.

## Goal

Design local trace v1 before implementation.

## Non-Goals

- Do not implement trace storage.
- Do not capture full prompts or tool payloads by default.
- Do not add cloud upload, telemetry, or remote trace services.
- Do not change session persistence behavior yet.

## Implementation Notes

The design must define:

- trace schema
- redaction policy
- JSONL or bundle storage choice
- relation to `TurnAuditCapture`
- relation to `TurnPolicyTrace`
- relation to `/explain-last-turn`
- CLI/readability requirements
- deterministic tests for trace schema

The trace must answer:

- what task contract was resolved?
- what phase was selected?
- what tools were visible?
- what tool calls were attempted?
- what was blocked and why?
- was approval required, granted, or denied?
- what changed?
- what verification ran?
- what outcome was reported?

## Acceptance Criteria

- A trace design document exists.
- Default trace redaction avoids full sensitive payloads.
- Full prompt/tool payload capture is opt-in debug behavior.
- Trace storage is local-only.
- The design includes test cases for schema stability and redaction.
- The design identifies compatibility with existing turn logs and session files.
- No runtime implementation is included.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

## Work-Test Cycle Notes

Use the inner dev loop. This is design-only and should unblock T33.

## Known Risks

- Over-capturing local file content would weaken user trust.
- Under-capturing would make traces useless for debugging policy failures.
