# [T559] Extract command trace event factory

## Summary

T559 extracts command-specific local trace event construction from
`LocalTurnTraceCapture` into a dedicated package-local owner:

```text
dev.talos.runtime.trace.CommandTraceEventFactory
```

The public `LocalTurnTraceCapture.recordCommand...` facade remains in place.
Runtime behavior, command policy, approval flow, checkpoint behavior, command
execution, command output rendering, trace persistence, private-document
handoff, and artifact canary behavior are unchanged.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 159f3f33
talosVersion = 0.9.9
```

Predecessor:

```text
T558 = Local trace evidence ownership decision
```

## What changed

### Added `CommandTraceEventFactory`

`CommandTraceEventFactory` now owns command trace event construction:

- `COMMAND_PLAN_CREATED`
- `COMMAND_POLICY_DECISION`
- `COMMAND_APPROVAL_REQUIRED`
- `COMMAND_APPROVAL_GRANTED`
- `COMMAND_APPROVAL_DENIED`
- `COMMAND_DENIED`
- `COMMAND_STARTED`
- `COMMAND_OUTPUT_TRUNCATED`
- `COMMAND_KILLED`
- `COMMAND_TIMED_OUT`
- `COMMAND_COMPLETED`
- `COMMAND_FAILED`

It also owns command trace payload construction:

- profile id;
- risk;
- cwd hash;
- cwd leaf;
- capped display argv;
- argv hash;
- timeout;
- stdout/stderr output limits;
- expected write count;
- checkpoint requirement;
- network and interactive flags;
- exit code;
- duration;
- timeout/killed flags;
- stdout/stderr byte counts;
- stdout/stderr hashes;
- stdout/stderr truncation flags;
- redaction-applied flag;
- error hash.

Raw stdout and stderr are still not stored in local trace events.

### Slimmed `LocalTurnTraceCapture`

`LocalTurnTraceCapture` still owns the thread-local facade and trace lifecycle.
It now delegates command event construction to `CommandTraceEventFactory`.

It no longer owns:

- `CommandToolPlanner.displayCommand(...)`;
- command event type string literals;
- `commandPlanData(...)`;
- `commandResultData(...)`;
- command display string capping.

### Added ownership regression

`LocalTurnTraceCommandTest.commandTraceEventConstructionIsOwnedByFactory()`
asserts:

- the factory exists;
- `LocalTurnTraceCapture` delegates to `CommandTraceEventFactory`;
- `LocalTurnTraceCapture` no longer imports `CommandToolPlanner`;
- `LocalTurnTraceCapture` no longer owns command plan/result payload helpers;
- `LocalTurnTraceCapture` no longer contains command event type string
  literals;
- the factory owns command display and final command event names.

## TDD evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --no-daemon
```

Expected failure:

```text
LocalTurnTraceCommandTest > commandTraceEventConstructionIsOwnedByFactory() FAILED
AssertionFailedError at LocalTurnTraceCommandTest.java:126
```

The failure was caused by the missing dedicated command trace event owner.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --no-daemon
```

Result:

```text
BUILD SUCCESSFUL
```

## Behavioral preservation

Existing command trace behavior remains covered by
`LocalTurnTraceCommandTest`:

- command lifecycle trace events are still recorded;
- command denied-before-approval is still recorded;
- raw command stdout is not stored in trace JSON;
- raw command stderr is not stored in trace JSON;
- command failure payload still records exit code;
- command failure payload still records redaction-applied status.

T559 intentionally does not move:

- trace lifecycle begin/complete/clear;
- context-ledger lifecycle coupling;
- trace persistence;
- prompt-debug capture;
- private-document handoff trace events;
- trace redaction;
- artifact canary scanning;
- command runtime execution or rendering.

## Next move

Do not assume T560 is another event-family extraction.

The next correct move is to inspect the post-T559 local trace evidence shape
from fresh beta. The likely next candidate is private-document handoff event
construction, but that touches approval, privacy, content metadata, and
model-context handoff semantics. It must be rechecked from current source before
implementation.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceCommandTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.command.*" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
