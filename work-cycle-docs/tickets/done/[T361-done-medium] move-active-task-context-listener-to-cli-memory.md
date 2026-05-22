# [T361-done-medium] Move Active Task Context Listener To CLI Memory

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T361`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T360-done-medium] move-cli-approval-gate-adapter`

## Evidence Summary

- Source: post-T360 implementation after PR #25 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `c86491f5546921c5a9bd8ec2a8b15bfca77b1939`.
- Beta push CI: run `#71`, `Beta Dev CI`, push event for `c86491f5`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T361`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved the concrete active-task session-memory listener from runtime
    ownership to `dev.talos.cli.repl`;
  - kept the runtime `SessionListener` contract and
    `ActiveTaskContextUpdater` policy derivation in runtime;
  - moved `ActiveTaskContextUpdateListenerTest` with the adapter;
  - kept `TalosBootstrap` wiring behavior unchanged;
  - architecture baseline reduced by one stale entry.
- Verification status: passed.

## Problem

`ActiveTaskContextUpdateListener` was a concrete adapter between runtime turn
completion events and `SessionMemory` mutation.

Runtime owns:

```text
dev.talos.runtime.SessionListener
dev.talos.runtime.TurnResult
dev.talos.runtime.context.ActiveTaskContextUpdater
```

CLI/REPL currently owns:

```text
dev.talos.cli.repl.SessionMemory
```

Keeping the adapter in runtime forced runtime to import CLI session memory:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/ActiveTaskContextUpdateListener.java|dev.talos.cli.repl.SessionMemory
```

That was the same shape as T360: a concrete composition adapter was sitting on
the wrong side of the boundary.

## Change

T361 moves the listener adapter to:

```text
dev.talos.cli.repl.ActiveTaskContextUpdateListener
```

The listener still implements the runtime `SessionListener` contract and still
delegates active-task derivation to runtime `ActiveTaskContextUpdater`. Its
behavior is unchanged:

- proposal follow-up context updates;
- denied-mutation follow-up context updates;
- verifier-failure context updates;
- artifact-goal updates;
- change-summary context updates;
- null-memory no-op behavior.

`TalosBootstrap` continues to register the listener after
`MemoryUpdateListener`.

## Baseline Result

Architecture baseline moved:

```text
36 -> 35
```

Removed entry:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/ActiveTaskContextUpdateListener.java|dev.talos.cli.repl.SessionMemory
```

## Tests Updated

- `ActiveTaskContextUpdateListenerTest` moved to `dev.talos.cli.repl`.
- `TalosBootstrapWiringTest` now resolves the listener from its own package.

## Verification

- RED architecture ratchet:
  `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with the removed listener-to-`SessionMemory` baseline row.
- Focused GREEN test run:
  `.\gradlew.bat test --tests "dev.talos.cli.repl.ActiveTaskContextUpdateListenerTest" --tests "dev.talos.cli.repl.TalosBootstrapWiringTest" --no-daemon`:
  passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.

## Next Correct Ticket

Do not mechanically move `SessionMemory` yet. It has broad responsibilities:
conversation turns, tool evidence, active-task state, artifact goal state,
change-summary state, failed workspace-switch state, and pending mutation
confirmation state.

After T361, inspect the remaining `35` baseline entries. The next likely
decision point is the larger runtime result/session-memory boundary:

- runtime still emits and consumes CLI `Result`;
- runtime still consumes CLI `Context`;
- `ConversationManager` still depends on CLI `SessionMemory`;
- several runtime listeners still adapt CLI result/memory types.

That cluster needs either another adapter-local move or a short ownership
decision ticket before a larger extraction.

Confidence: high.
