# [T362-done-medium] Move Active Task Context Updater To CLI Memory

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T362`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T361-done-medium] move-active-task-context-listener-to-cli-memory`

## Evidence Summary

- Source: post-T361 implementation after PR #26 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `3e1a182c03bd2e496dc8d90697dafb6048243f73`.
- Beta push CI: run `#74`, `Beta Dev CI`, push event for `3e1a182c`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T362`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved the result-aware active-task updater from runtime ownership to
    `dev.talos.cli.repl`;
  - kept active-task value records and prompt context policy in
    `dev.talos.runtime.context`;
  - kept the CLI listener behavior unchanged;
  - moved `ActiveTaskContextUpdaterTest` with the updater;
  - architecture baseline reduced by one stale entry.
- Verification status: passed.

## Problem

After T361, the concrete session-memory listener lived beside
`SessionMemory`, but its updater still lived in runtime while directly
consuming CLI result types:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/context/ActiveTaskContextUpdater.java|dev.talos.cli.repl.Result
```

That updater is not a general runtime context primitive. It derives
session-memory follow-up state from a completed turn result, including
renderable `Result.Ok` / `Result.Streamed` text. Its only production caller is
the CLI session-memory listener.

## Change

T362 moves:

```text
dev.talos.runtime.context.ActiveTaskContextUpdater
```

to:

```text
dev.talos.cli.repl.ActiveTaskContextUpdater
```

The runtime context value types remain in runtime:

```text
dev.talos.runtime.context.ActiveTaskContext
dev.talos.runtime.context.ArtifactGoal
dev.talos.runtime.context.ChangeSummaryContext
dev.talos.runtime.context.ActiveTaskContextPolicy
```

This preserves the separation:

- runtime owns the durable context value model and policy used by prompt
  construction;
- CLI/REPL owns the adapter that turns renderable CLI results plus runtime
  turn audit facts into `SessionMemory` state.

## Baseline Result

Architecture baseline moved:

```text
35 -> 34
```

Removed entry:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/context/ActiveTaskContextUpdater.java|dev.talos.cli.repl.Result
```

## Tests Updated

- `ActiveTaskContextUpdaterTest` moved to `dev.talos.cli.repl`.
- `ActiveTaskContextUpdateListener` now uses the updater from its own package.

## Verification

- RED architecture ratchet:
  `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with the removed updater-to-`Result` baseline row.
- Focused GREEN test run:
  `.\gradlew.bat test --tests "dev.talos.cli.repl.ActiveTaskContextUpdaterTest" --tests "dev.talos.cli.repl.ActiveTaskContextUpdateListenerTest" --tests "dev.talos.cli.repl.TalosBootstrapWiringTest" --no-daemon`:
  passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.

## Next Correct Ticket

Do not move `SessionMemory` mechanically. It still mixes conversation turns,
tool evidence, active-task context, artifact goals, change-summary context,
workspace-switch state, and pending mutation confirmation.

After T362, inspect the remaining `34` baseline entries. The highest-leverage
remaining cluster is still the runtime/CLI result and context boundary:

- runtime emits and consumes `dev.talos.cli.repl.Result`;
- runtime consumes `dev.talos.cli.repl.Context`;
- core conversation management still depends on `SessionMemory`;
- command/workspace and SPI edges remain separate design tracks.

The next ticket should either isolate one more adapter-local result edge or
pause for a short ownership decision around `Result`, `Context`, and
`SessionMemory`.

Confidence: high.
