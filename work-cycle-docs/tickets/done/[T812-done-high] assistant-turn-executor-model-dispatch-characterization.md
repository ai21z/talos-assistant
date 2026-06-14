# T812 - AssistantTurnExecutor Model Dispatch Characterization

Status: done
Severity: high
Release gate: no - Wave 5 characterization ticket
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-14
Owner: Codex

## Problem

T811 extracted turn preparation from `AssistantTurnExecutor`, but the executor
still owns model dispatch for streaming and non-streaming turns, provider
request controls, context-budget fallback, escalated retry dispatch, spinner
completion, and the handoff into tool-loop and no-tool answer paths.

The generated architecture intelligence report still ranks
`cli.modes.AssistantTurnExecutor` first after T811. The point-in-time priority
index is review-order evidence, not a success metric or extraction mandate.

## Required Behavior

1. Characterize model dispatch behavior before any production extraction.
2. Preserve provider request controls, including tool choice and sampling.
3. Preserve streaming versus non-streaming final-answer shape.
4. Preserve tool-only streaming completion ordering before tool-loop execution.
5. Keep provider-body and prompt-debug capture at the downstream client/engine
   boundary: `LlmClient`, `OllamaChatClient`, and `CompatChatClient`.
6. Treat any surprising current behavior as characterization evidence, not as a
   reason to modify production code in this ticket.

## Non-Goals

- No `TurnModelDispatcher` implementation.
- No production code extraction.
- No package-cycle cleanup.
- No public CLI/product API change.
- No `SetupCmd.java` edit.
- No Qodana task, config, version, mode, or evidence change.
- No release candidate recut.

## Architecture Metadata

- Capability: Wave 5 model-dispatch characterization.
- Operation(s): deterministic unit characterization tests and ticket evidence.
- Owning package/class:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`.
- Related evidence:
  `work-cycle-docs/tickets/done/[T811-done-high] assistant-turn-executor-lifecycle-ownership-characterization.md`
  and
  `build/reports/talos/architecture-intelligence/current/11-wave5-ticket-sequence.md`.
- Risk and approval: high, because dispatch owns provider request controls,
  streaming completion, retry dispatch, and tool-loop/no-tool branching.
- Protected path behavior: no new protected reads or artifact writes.
- Checkpoint behavior: no mutation/checkpoint semantic changes.
- Evidence obligation: T812 must add characterization tests before T813 can
  extract a model dispatcher.
- Verification profile: focused `dev.talos.cli.modes.*` tests, then `check`;
  run `wikiEvidenceCloseGate` if wiki/report claims are updated.
- Allowed refactor scope: tests and ticket/wiki metadata only.

## Acceptance Criteria

- A focused test class characterizes normal non-streaming mutation dispatch
  provider controls.
- A focused test characterizes zero-temperature sampling for escalated
  missing-mutation retry dispatch.
- A focused test characterizes streaming and buffered no-tool final-answer
  shape.
- A focused test characterizes tool-only streaming completion ordering before
  tool-loop execution.
- No production code moves or behavior changes are made in T812.
- T813 remains the first possible production extraction ticket for model
  dispatch.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorModelDispatchCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Current Evidence

- T811 committed the turn-preparation extraction at
  `0ae6f3084fc3274a7682c73a454b35c952d86639`.
- Current generated architecture evidence after T811 records
  `cli.modes.AssistantTurnExecutor` first with priority index `401`.
- Model dispatch source anchors remain in `AssistantTurnExecutor`:
  streaming dispatch, non-streaming dispatch, provider request controls,
  exact-write fallback dispatch, and escalated retry dispatch.
- Provider-body/prompt-debug capture exists downstream of executor dispatch in
  `LlmClient`, `OllamaChatClient`, and `CompatChatClient`; T812 and T813 should
  not relocate it.

## Completion State

- T812 characterization was committed at
  `bde6081bcf57880812ab089a037624473440e0f4`.
- `AssistantTurnExecutorModelDispatchCharacterizationTest` now pins:
  - normal non-streaming mutation dispatch provider controls;
  - zero-temperature sampling for escalated missing-mutation retry dispatch;
  - streaming and buffered no-tool final-answer shape;
  - tool-only streaming completion ordering before tool-loop execution.
- T812 made no production code moves and did not implement `TurnModelDispatcher`.
- The next Wave 5 ticket should be T813, the first production model-dispatch
  extraction, guarded by the T812 tests.
