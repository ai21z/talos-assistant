# [T32-done-high] Ticket: Design Local Turn Trace Model V1
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/02-runtime-policy-ownership-map.md`
- `docs/architecture/03-local-turn-trace-model-v1.md`

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

## Current Code Read

- `docs/architecture/01-execution-discipline-and-local-trust.md`
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

## Planned Evidence

- Create `docs/architecture/03-local-turn-trace-model-v1.md`.
- Run `./gradlew.bat test --no-daemon`.

## Implementation Summary

- Created `docs/architecture/03-local-turn-trace-model-v1.md`.
- Documented current trace/audit/session pieces accurately:
  `TurnAuditCapture`, `TurnPolicyTrace`, `TurnAudit`, `TurnRecord`,
  `TurnResult`, `TurnTraceCapture`, session JSON/JSONL persistence, `/last`,
  debug trace display, and e2e harness capabilities.
- Defined the local trace v1 purpose, non-goals, schema, event model,
  redaction policy, storage recommendation, session compatibility,
  `/last`/`/explain-last-turn` relationship, T33 test strategy, migration
  path, risks, and open questions.
- Recommended one local JSON file per completed turn under session-owned trace
  storage, with existing session snapshots and turn logs left unchanged.
- No runtime behavior was changed.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

```powershell
./gradlew.bat test --no-daemon
```

Result: PASS (`BUILD SUCCESSFUL`; task was up-to-date).

## Manual Talos Check Result

Not required. This ticket is design-only and does not change runtime behavior.

## Known Follow-Ups

- T33 should implement the v1 model incrementally from existing
  `TurnAuditCapture`, `TurnPolicyTrace`, `TurnProcessor`,
  `AssistantTurnExecutor`, `ExecutionOutcome`, `JsonTurnLogAppender`, and
  `/last` seams.
- T33 should add trace model serialization/redaction tests before persistence
  wiring.
- `/session clear` trace-artifact cleanup must be handled in T33 or called out
  as a follow-up if not included.
