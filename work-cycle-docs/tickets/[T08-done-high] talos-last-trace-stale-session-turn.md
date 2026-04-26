# [done] Ticket: Last Trace Shows Stale Session Turn In Fresh Process
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/tickets/talos-cli-last-run-introspection.md`
- `work-cycle-docs/tickets/talos-current-turn-debug-trace.md`

## Why This Ticket Exists

Manual QA depends on `/last trace` as a source of truth. The installed
mode/tool smoke run showed `/last trace` returning the previous saved session's
latest turn instead of the turn that just completed in the current process.

## Problem

Prompt sequence in a fresh Talos process:

```text
/debug trace
/mode ask
hello
/last trace
```

Observed after `hello`:

```text
Last Turn
  Turn:      5
  User Request
    Can you summarize what changed in plain English?
```

The visible current turn was:

```text
hello
Current Turn Trace
  contract: SMALL_TALK
```

The startup banner said a saved session existed but was not loaded:

```text
saved session found: 5 prior exchanges ... Not loaded.
```

So `/last trace` is mixing persisted saved-session turns with the current
not-loaded process state, which makes debug evidence misleading.

## Goal

`/last trace` should report the latest completed turn in the active process or
clearly state when it is showing persisted saved-session data.

## Scope

### In scope

- Align `/last` with active session-load semantics.
- Ensure a current-process turn is available to `/last` immediately after it
  completes.
- Add tests for saved-session-not-loaded behavior.

### Out of scope

- Redesigning session persistence.
- Removing saved-session discovery.

## Proposed Work

1. Inspect how `ExplainLastTurnCommand` loads turns from `JsonSessionStore`.
2. Decide whether `/last` should:

   - use an in-memory latest-turn pointer first, or
   - filter persisted turns by active loaded session state, or
   - print a clear "saved session not loaded" warning.

3. Add tests:

   ```text
   saved session exists but not loaded -> new current turn -> /last reports new current turn
   saved session exists but no current turn -> /last explains persisted data state
   ```

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/runtime/TurnRecord.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest"
```

Installed CLI check:

```text
/debug trace
hello
/last trace
```

with an existing saved session present but not loaded.

## Acceptance Criteria

- `/last trace` reports the current process's latest completed turn after a
  turn completes.
- If it uses persisted data, the output labels that fact.
- Manual QA can trust `/last trace` without separately auditing session files.

## Resolution Notes

`ExplainLastTurnCommand` now receives the active process start time from
`TalosBootstrap` and filters persisted turn records to the active process.
If saved turns exist but none belong to the current process, `/last` reports
that saved history exists but was not loaded instead of showing it as current.

Coverage:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --tests "dev.talos.cli.repl.TalosBootstrapWiringTest"
```
