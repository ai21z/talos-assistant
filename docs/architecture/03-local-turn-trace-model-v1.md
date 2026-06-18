# Local Turn Trace Model V1

Date: 2026-04-28
Status: design for T33 implementation
Parent architecture: `docs/architecture/01-execution-discipline-and-local-trust.md`
Policy map: `docs/architecture/02-runtime-policy-ownership-map.md`

## 1. Purpose

Local trace v1 is Talos's local black-box recorder for a single turn.

It should make an executed turn explainable without trusting model prose,
without uploading anything, and without forcing the user to inspect a raw
session transcript. The trace is local evidence for execution discipline.

Current integrity boundary: Local traces and logs are durable evidence artifacts, but they are not tamper-evident. Local traces and turn logs are best-effort plaintext diagnostic artifacts stored under `~/.talos`. They are NOT tamper-evident: there is no hash chain, signature, or append-only enforcement, so any process with write access to your home directory can alter or delete them undetectably. Treat them as local debugging evidence, not as a cryptographically provable audit trail.

It must help answer:

- what task contract was resolved?
- what phase was selected?
- what tools were visible?
- what tool calls were attempted?
- what was blocked and why?
- was approval required, granted, or denied?
- what changed?
- what verification ran?
- what outcome was reported?

The trace is not a second conversation memory. It is a structured local
diagnostic artifact that lets `/last trace`, future `/explain-last-turn`, the
scenario harness, and manual QA explain what Talos did and did not do.

## 2. Current State

Talos already has several trace-like pieces. They are useful, but together
they are not yet a first-class turn trace.

### `TurnAuditCapture`

`TurnAuditCapture` is a thread-local per-turn bag started in
`TurnProcessor.process`. It collects:

- `TurnRecord.ToolCallSummary` values in call order
- compact policy block strings
- one `TurnPolicyTrace`
- approval counters: required, granted, denied

`TurnProcessor.executeTool` writes tool-call, approval, and block information
into this bag. `TurnAuditCapture.end()` produces immutable `TurnAudit` and
clears the thread-local.

Limitations:

- It records summaries, not structured event chronology.
- It stores block reasons as strings.
- It does not record model response boundaries, protocol sanitization, repair
  decisions, or verification events as explicit events.

### `TurnPolicyTrace`

`TurnPolicyTrace` is a compact structured policy snapshot. It stores:

- task type
- mutation allowed
- verification required
- expected targets
- forbidden targets
- initial phase
- final phase
- native tool names
- prompt tool names
- block strings

`AssistantTurnExecutor.recordPolicyTrace` records this from the resolved
`TaskContract`, current phase, and selected native tools.

Limitations:

- It is a snapshot, not an event timeline.
- It does not contain session, model, verification, approval, protocol, repair,
  or outcome objects.
- It intentionally avoids raw prompt/tool payloads, which is good for privacy
  but insufficient for detailed local debugging.

### `TurnAudit`

`TurnAudit` is the immutable audit snapshot attached to `TurnResult`. It
contains:

- tool-call summaries
- approval counters
- `TurnPolicyTrace`

It is the current carrier between runtime execution and persistence/rendering.

Limitations:

- It does not expose typed event details.
- It has no trace id.
- It does not reference a separate durable trace artifact.

### `TurnRecord`

`TurnRecord` is the durable per-turn session record written to
`<sessionId>.turns.jsonl`. It stores:

- turn number
- timestamp
- duration
- raw user input
- committed assistant text
- tool-call summaries
- approval counters
- retrieval trace summary
- status tag
- compact policy trace

This is currently more transcript than trace. It is useful for session replay
and `/last`, but it stores raw user input and assistant text because session
history needs those fields. Local trace v1 should not duplicate full prompt or
assistant content by default.

### `TurnResult`

`TurnResult` returns the renderable `Result`, retrieval trace, turn number,
elapsed duration, and `TurnAudit`. It is the current boundary between
`TurnProcessor` and the CLI/persistence listeners.

T33 can add trace identity here only if needed, but should avoid destabilizing
existing constructors and tests.

### `TurnTraceCapture`

`TurnTraceCapture` is a thread-local holder for `RetrievalTrace` only. Despite
the name, it is not the turn trace model. T33 should avoid overloading this
class with full trace responsibility. A new `dev.talos.runtime.trace` package
or clearly named `LocalTurnTrace*` types would avoid confusion.

### `TurnUserRequestCapture`

`TurnUserRequestCapture` carries the current user request to tool execution
for guards such as `ScopeGuard`. It currently stores raw text in a
thread-local. Local trace v1 should not persist this raw text by default.

### `TurnTaskContractCapture`

`TurnTaskContractCapture` carries the resolved `TaskContract` from executor to
`TurnProcessor.executeTool`, so tool execution uses the same contract as the
executor and trace. It is an important seam for trace v1 because it proves the
contract that controlled the tool gateway.

### `JsonTurnLogAppender` and `JsonSessionStore`

`JsonTurnLogAppender` appends one `TurnRecord` after each completed turn.
`JsonSessionStore` writes:

- `<sessionId>.json` for the session snapshot
- `<sessionId>.turns.jsonl` for append-only turn records

The current turn log is deliberately additive and failure-tolerant; write
errors are logged and do not fail a live turn.

Trace v1 should preserve that posture: traces are local evidence and should
not break normal execution unless a future explicit debug mode requires
fail-closed behavior.

### `/last` / `/explain-last-turn`

`ExplainLastTurnCommand` registers as `explain-last-turn` with aliases
`explain` and `last`. It renders:

- summary view
- tools view
- sources view
- trace view

Current `/last trace` is built from `TurnRecord`, `TurnPolicyTrace`, tool-call
summaries, approval counts, and retrieval summary. It does not read a separate
trace file.

`ReplRouter` also prints a compact "Current Turn Trace" when debug level is
`TRACE`. That display uses `TurnResult.audit().policyTrace()`.

### E2E scenario harness

The scenario harness can assert:

- tool names and counts
- approval counts
- file changes
- final answer text
- persisted turn log existence and content for persistence scenarios

It does not yet assert a first-class trace artifact. T33 should add a small
trace assertion surface without inventing a second scenario framework.

## 3. Non-Goals

Local trace v1 does not include:

- cloud tracing
- telemetry
- remote upload
- full prompt capture by default
- full assistant answer capture by default
- full tool payload capture by default
- screenshots or browser traces
- shell execution traces, because shell execution is not in scope
- checkpoint implementation
- browser automation
- MCP event streaming
- multi-agent orchestration traces
- a replacement for session replay or conversation memory

Trace v1 must stay local, bounded, and privacy-aware.

## 4. Trace Schema V1

Trace schema v1 should be Java-friendly and JSON-friendly. The top-level
object should be a per-turn bundle.

Recommended package direction for T33:

- `dev.talos.runtime.trace.LocalTurnTrace`
- `dev.talos.runtime.trace.TurnTraceEvent`
- `dev.talos.runtime.trace.TraceRedactionMode`
- `dev.talos.runtime.trace.LocalTurnTraceRecorder`
- `dev.talos.runtime.trace.JsonTurnTraceStore`

Suggested top-level schema:

```json
{
  "schemaVersion": 1,
  "traceId": "trc_20260428_000001_ab12cd34",
  "sessionId": "workspace-path-sha1",
  "turnNumber": 12,
  "timestamp": "2026-04-28T12:34:56Z",
  "workspace": {
    "id": "workspace-path-sha1",
    "pathMode": "HASH_ONLY",
    "displayPath": "",
    "rootHash": "sha256:..."
  },
  "mode": "auto",
  "model": {
    "backend": "ollama",
    "name": "qwen2.5-coder:14b"
  },
  "taskContract": {
    "type": "FILE_CREATE",
    "mutationRequested": true,
    "mutationAllowed": true,
    "verificationRequired": true,
    "expectedTargets": ["index.html", "styles.css", "scripts.js"],
    "forbiddenTargets": []
  },
  "phaseTransitions": [
    {"from": "INSPECT", "to": "APPLY", "reason": "mutationAllowed"}
  ],
  "toolSurface": {
    "nativeTools": ["talos.read_file", "talos.write_file", "talos.edit_file"],
    "promptTools": ["talos.read_file", "talos.write_file", "talos.edit_file"],
    "hiddenTools": [],
    "selectionReason": "mutation task in APPLY phase"
  },
  "events": [],
  "verification": {
    "status": "FAILED",
    "summary": "Static verification failed",
    "problemCount": 2,
    "problemSummaries": ["scripts.js was not created"]
  },
  "repair": {
    "decision": "NOT_APPLICABLE",
    "planId": ""
  },
  "checkpoint": {
    "decision": "NOT_IMPLEMENTED",
    "checkpointId": ""
  },
  "outcome": {
    "completionStatus": "FAILED",
    "taskCompletionStatus": "FAILED",
    "groundingStatus": "UNKNOWN",
    "mutationStatus": "PARTIAL",
    "reportedToUser": "TASK_INCOMPLETE"
  },
  "warnings": [
    {"type": "STATIC_VERIFICATION_FAILED", "message": "Static post-apply verification failed."}
  ],
  "redaction": {
    "mode": "DEFAULT",
    "fullPromptCaptured": false,
    "fullAssistantCaptured": false,
    "fullToolPayloadCaptured": false
  }
}
```

Required fields:

- `schemaVersion`
- `traceId`
- `sessionId` when available
- `turnNumber`
- `timestamp`
- `workspace`
- `mode`
- `model`
- `taskContract`
- `phaseTransitions`
- `toolSurface`
- `events`
- `verification`
- `repair`
- `checkpoint`
- `outcome`
- `warnings`
- `redaction`

### Trace ids and timestamps

Production trace ids can use a timestamp plus random or monotonic suffix.
Tests need deterministic injection.

T33 should define a small seam:

- `TraceIdGenerator`
- `TraceClock`

The default can use `Instant.now()` and randomness. Tests can provide fixed
values. This avoids brittle tests while keeping production trace ids unique.

### Workspace identity

Default trace should identify the workspace by hash, not by absolute path.

Recommended default:

- `workspace.id`: the existing `JsonSessionStore.sessionIdFor(workspace)` or a
  future stable workspace hash
- `workspace.pathMode`: `HASH_ONLY`
- `workspace.displayPath`: blank by default

Debug/full mode may include a redacted or absolute path only when explicitly
configured.

## 5. Event Model

Trace v1 should use a small extensible event model. The events are ordered and
append-only inside a turn.

Recommended event shape:

```json
{
  "type": "TOOL_CALL_BLOCKED",
  "at": "2026-04-28T12:34:57Z",
  "phase": "INSPECT",
  "message": "task-contract read-only denied talos.write_file",
  "data": {
    "tool": "talos.write_file",
    "pathHint": "index.html",
    "risk": "WRITE",
    "reasonCode": "TASK_CONTRACT_READ_ONLY"
  }
}
```

V1 event types:

- `TRACE_STARTED`
- `TASK_CONTRACT_RESOLVED`
- `PHASE_SET`
- `TOOL_SURFACE_SELECTED`
- `MODEL_RESPONSE_RECEIVED`
- `TOOL_CALL_PARSED`
- `TOOL_CALL_BLOCKED`
- `APPROVAL_REQUIRED`
- `APPROVAL_GRANTED`
- `APPROVAL_DENIED`
- `TOOL_EXECUTED`
- `PROTOCOL_SANITIZED`
- `VERIFICATION_STARTED`
- `VERIFICATION_COMPLETED`
- `OUTCOME_RENDERED`
- `TRACE_COMPLETED`

Future placeholder event types:

- `REPAIR_DECISION_RECORDED`
- `CHECKPOINT_CREATED`
- `CHECKPOINT_FAILED`
- `CHECKPOINT_RESTORED`

Do not overbuild v1. Events should be easy to serialize as maps or records.
They should not require a graph model or nested spans.

## 6. Redaction Policy

Trace v1 must default to redaction.

### Default mode

Default trace may store:

- tool names
- tool risk category
- normalized relative paths inside the workspace
- safe path hints
- file sizes
- content hashes
- line counts
- result status
- block reason codes and short messages
- approval status
- verification status
- verification problem summaries
- outcome status
- counts of tokens/chars/tool calls when available

Default trace must not store:

- full user prompt
- full assistant answer
- full file contents
- full write payloads
- full edit `old_string` / `new_string`
- secrets or secret-like path content
- absolute user home paths
- raw model protocol text
- full retrieval snippets

### Path redaction

Safe default path behavior:

- If a path is inside the workspace, store normalized relative path.
- If a path escapes the workspace, store only a redacted marker such as
  `<outside-workspace>` and the block reason.
- If a path looks secret-like, store only a coarse hint such as
  `<protected-path>` plus extension when safe.

Secret-like paths include, but are not limited to:

- `.env`
- `.env.*`
- paths containing `secret`
- paths containing `token`
- paths containing `credential`
- private key names
- SSH key paths

The exact protected-path policy belongs to T34/T35. Trace v1 should design for
that input rather than hardcode the final list.

### Content redaction

For tool payloads:

- Store `contentHash`, `contentBytes`, and `contentLines` for write payloads.
- Store `oldStringHash`, `newStringHash`, and length/line counts for edit
  payloads.
- Store no raw content in default mode.

For model and user text:

- Store `promptHash` and `promptChars`, not full prompt.
- Store `assistantHash` and `assistantChars`, not full final answer.
- Store `protocolShape` and `protocolSanitizationStatus` when protocol text is
  present, not raw protocol.

### Debug/full mode

Optional debug/full capture:

- is local only
- requires explicit user or config opt-in
- must be marked in `redaction.mode`
- must never be enabled by model output
- should be visible in `/status --verbose`
- should be easy to disable

Even in full mode, protected-path defaults should still redact known secret
files unless a future explicit override says otherwise.

## 7. Storage Format

Recommendation: v1 should write one JSON file per completed turn.

Recommended path:

```text
~/.talos/sessions/traces/<sessionId>/<turnNumber>-<traceId>.json
```

Why one JSON file per turn:

- A turn trace is naturally a bounded bundle.
- `/last trace` can load the latest trace file directly.
- Manual QA can attach one file path or trace id to a transcript.
- Event arrays are easier to inspect than huge escaped JSONL rows.
- A malformed trace file affects one turn, not a whole session trace stream.
- Trace files can be deleted per session without touching conversation
  snapshots.

Compatibility with existing JSONL:

- Keep `<sessionId>.turns.jsonl` as the durable turn log.
- Add trace storage as a companion artifact.
- Optionally add `traceId` and `tracePathHint` to future `TurnRecord` rows, but
  only as backward-compatible optional fields.

Alternative considered: one trace JSONL event stream per session.

Why not v1 default:

- It complicates `/last trace` lookup.
- It makes per-turn manual artifact review harder.
- It increases the risk that a malformed line or partial write creates
  confusing trace gaps across turns.

JSONL may still be useful later as an index:

```text
~/.talos/sessions/traces/<sessionId>/index.jsonl
```

That index should be optional and derived from per-turn trace bundles, not the
primary trace truth for v1.

## 8. Relationship To Existing Session Files

Trace v1 is additive.

Existing files stay valid:

- `~/.talos/sessions/<sessionId>.json`
- `~/.talos/sessions/<sessionId>.turns.jsonl`

Existing behavior stays valid:

- session snapshot save/load
- turn-log append/load
- turn-log replay fallback
- `/session clear`
- `/session load`
- `/last summary`
- `/last tools`
- `/last sources`
- `/last trace`

T33 should not require trace files for normal session replay. If a trace file is
missing, `/last trace` should fall back to current `TurnRecord` rendering and
say that the full local trace file is unavailable.

Deletion behavior:

- `/session clear` should eventually delete trace artifacts for that session.
- If T33 does not update `/session clear`, it must create a follow-up ticket and
not hide the leftover-artifact risk.

Persistence failure behavior:

- Trace persistence should be best-effort by default.
- Failure to write a trace must not fail the live turn.
- Future explicit debug/audit modes can opt into stricter behavior, but that is
not v1 default.

## 9. Relationship To `/last` And Future `/explain-last-turn`

Current command:

- `ExplainLastTurnCommand` implements `explain-last-turn`
- aliases include `explain` and `last`
- usage is `/last [summary|tools|sources|trace|--verbose]`

Future v1 display should keep the current simple views and enrich trace view
when a trace file exists.

Recommended `/last trace` sections:

```text
Last Turn Trace

  Trace id:      trc_20260428_000001_ab12cd34
  Trace file:    ~/.talos/sessions/traces/<sessionId>/...
  Turn:          12
  Status:        ok
  Outcome:       TASK_INCOMPLETE

Task
  Contract:      FILE_CREATE
  Mutation:      requested=true allowed=true
  Verification:  required=true
  Expected:      index.html, styles.css, scripts.js

Phases
  INSPECT -> APPLY -> VERIFY -> RESPOND

Tools
  Visible:       talos.read_file, talos.write_file, talos.edit_file
  Attempted:     talos.write_file index.html [ok]
                 talos.write_file scripts.js [failed]

Approvals
  Required:      2
  Granted:       2
  Denied:        0

Blocks
  none

Verification
  Status:        FAILED
  Problems:      scripts.js missing; HTML does not link JS

Outcome
  Reported:      task incomplete
  Warnings:      STATIC_VERIFICATION_FAILED
```

The user-facing display should avoid dumping raw event JSON by default. A future
`/last trace --json` can print the trace path or compact JSON only if explicitly
added.

`/debug trace` should remain concise. It can show trace id once v1 exists, but
should not print the whole event stream after every turn.

## 10. Test Strategy For T33

T33 should add deterministic tests before wiring broad persistence.

Required unit tests:

- schema serialization test:
  - create a `LocalTurnTrace` with representative fields
  - serialize to JSON
  - deserialize
  - assert schema version and core fields

- redaction default test:
  - record a write payload containing `SECRET=abc`
  - assert raw content is absent
  - assert hash/size/count are present

- no full prompt/tool payload by default:
  - record user prompt and tool payload
  - assert prompt text, assistant text, `old_string`, `new_string`, and
    `content` do not appear in JSON

- policy block captured:
  - record a `TASK_CONTRACT_READ_ONLY` block
  - assert event exists with tool, phase, and reason code

- approval captured:
  - record required, granted, and denied approval events
  - assert event order and counters

- mutating tool result captured without full content:
  - record `talos.write_file` success
  - assert path hint and content hash
  - assert raw file content absent

- verification result captured:
  - record static verification failed with two problem summaries
  - assert status and problem count

- deterministic trace id and timestamp override:
  - inject fixed id/clock
  - assert stable JSON output

- missing trace file fallback:
  - `/last trace` still renders current `TurnRecord` details when full trace
    artifact is unavailable

Required integration/e2e tests:

- scenario can assert trace id or trace summary:
  - executor path produces trace id attached to turn result or persisted record
  - trace summary includes task type, visible tools, approvals, blocks, and
    verification status

- scenario for read-only denied mutation:
  - blocked mutating tool call records `TOOL_CALL_BLOCKED`
  - no raw protocol payload in trace default mode

- scenario for approved mutation:
  - approval required/granted events appear
  - mutating tool executed event appears
  - changed path appears as relative path
  - content only appears as hash/count metadata

Existing tests to preserve:

- `TurnTraceCaptureTest`
- `JsonTurnLogAppenderTest`
- `JsonSessionStoreTurnsTest`
- `ExplainLastTurnCommandTest`
- `TurnProcessor*`
- `AssistantTurnExecutorTest`
- relevant JSON scenarios around approvals, policy blocks, and static
  verification

## 11. Migration And Compatibility

T33 can implement v1 incrementally.

Recommended sequence:

1. Add trace model types under `dev.talos.runtime.trace`.
2. Add JSON serialization tests for the model.
3. Add redaction helper tests.
4. Add a recorder that can be used like current thread-local captures, but
   keep it separate from `TurnTraceCapture`.
5. Bridge existing `TurnAuditCapture` events into trace events.
6. Add trace persistence as a new listener or as a companion to
   `JsonTurnLogAppender`.
7. Add optional `traceId` to `TurnResult` or `TurnAudit` only if required.
8. Add optional `traceId` / `tracePathHint` to `TurnRecord` as backward-
   compatible fields.
9. Update `/last trace` to display full trace when available, with fallback to
   current rendering.
10. Add scenario harness assertion support for trace summary or trace id.

Likely seams:

- `TurnAuditCapture`: current tool, approval, block, and policy trace source.
- `TurnPolicyTrace`: starting point for `TASK_CONTRACT_RESOLVED`,
  `PHASE_SET`, and `TOOL_SURFACE_SELECTED`.
- `TurnProcessor`: tool execution, approval, block, and policy enforcement
  events.
- `AssistantTurnExecutor`: task contract resolution, tool surface selection,
  model response, protocol sanitization, and outcome rendering events.
- `ExecutionOutcome`: verification result, truth warnings, completion status,
  task outcome.
- `JsonTurnLogAppender`: current post-turn persistence seam.
- `JsonSessionStore`: current session directory and session id helper.
- `ExplainLastTurnCommand`: user-facing trace display.
- Scenario runner/result classes: deterministic trace assertions.

Implementation caution:

- Do not make trace required for `TurnProcessor.process` to complete.
- Do not change existing `TurnRecord` constructor behavior in a way that breaks
  old JSONL reads.
- Do not store default trace artifacts inside the workspace.
- Do not reuse `TurnTraceCapture` for full trace v1; its name currently means
  retrieval trace, and overloading it would confuse the design.

## 12. Risks

### Over-capturing private local content

The biggest risk is storing full prompts, file contents, write payloads, or
secret paths by default. That would violate Talos's local trust posture even if
the files never leave the machine.

Mitigation:

- default redaction
- hashes/counts instead of content
- protected path redaction
- explicit full/debug mode only

### Under-capturing too little to debug

If trace v1 stores only the current `TurnPolicyTrace`, it will not explain why
a tool was blocked, why approval happened, or why verification failed.

Mitigation:

- typed event model
- reason codes
- verification summaries
- approval events
- tool result summaries

### Creating noisy traces nobody reads

A full event dump can be technically complete and practically useless.

Mitigation:

- `/last trace` renders a compact human summary
- raw JSON remains an artifact, not the primary UI
- event names and reason codes stay stable

### Making trace required for normal execution

Trace write failure must not break normal turns by default.

Mitigation:

- additive listener or best-effort store
- fallback to existing `TurnRecord`
- explicit future debug/audit mode for stricter behavior if needed

### Destabilizing session persistence

Changing `TurnRecord` or `JsonSessionStore` too aggressively could break session
replay and existing logs.

Mitigation:

- optional fields only
- old JSONL lines remain readable
- trace files separate from snapshot and turn log

### Coupling trace too tightly to current class names

Trace should record stable policy concepts, not every current helper method.

Mitigation:

- event types use policy concepts
- implementation may draw from current classes, but schema should not expose
  implementation class names as required fields

## 13. Open Questions

- Exact storage directory:
  - recommended: `~/.talos/sessions/traces/<sessionId>/`
  - T33 should confirm Windows path behavior and cleanup handling.

- Should trace id attach to `TurnResult`, `TurnAudit`, or `TurnRecord`?
  - `TurnAudit` is the current metadata carrier.
  - `TurnRecord` is the persisted display/replay record.
  - T33 should choose the smallest compatible seam.

- How much assistant final answer text should default trace store?
  - recommendation: hash and char count only.
  - `/last` can still use existing `TurnRecord.assistantText`.

- Should manual QA transcripts reference trace ids?
  - recommendation: yes, once T33 exists.
  - transcript files can include trace id and trace file path.

- Should the scenario runner assert full trace files or only summaries?
  - recommendation: start with trace summary/id assertions, then add one or two
    focused JSON artifact tests for redaction and event shape.

- Should retrieval snippets ever appear in full/debug trace?
  - default no.
  - full/debug mode can consider snippet hashes or paths first.

- Should trace persistence be controlled by a setting?
  - default local trace can be enabled once redacted.
  - full payload capture must be explicit opt-in.

## 14. T33 Entry Checklist

Before implementing T33:

- Add trace model tests first.
- Keep default trace redacted.
- Keep trace storage local-only.
- Keep existing session files compatible.
- Add `/last trace` enrichment behind fallback behavior.
- Do not introduce permissions, checkpointing, shell, browser, MCP, or repair
  controller work in the trace implementation ticket.
