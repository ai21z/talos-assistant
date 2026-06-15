# T818 AssistantTurnExecutor Prompt-Instruction Adapter Thinning

Status: open
Priority: high
Wave: 5
Owner: architecture/runtime policy ownership

## Summary

Remove the remaining `AssistantTurnExecutor.inject*` compatibility surface by
moving prompt-instruction injection to a runtime-owned helper and repointing all
repo callers.

This is behavior-preserving architecture work. It narrows
`AssistantTurnExecutor` and removes the `PromptInspector -> AssistantTurnExecutor`
dependency without changing public CLI/product behavior.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T818 HEAD:
  `d1d7eedde5cbef029f28e8d8be52a1a31e1ee11c`
- Talos version: `0.10.5`
- Prior guard ticket:
  `work-cycle-docs/tickets/done/[T817-done-high] assistant-turn-executor-no-tool-outcome-extraction.md`

The priority index remains review-order evidence only. T818 succeeds by
removing the wrong executor-owned adapter surface while preserving prompt
behavior and architecture boundaries.

## Scope

- Add public runtime owner
  `dev.talos.runtime.policy.CurrentTurnPromptInstructions`.
- Move prompt-instruction injection behavior out of
  `AssistantTurnPreparation`.
- Repoint `AssistantTurnPreparation`, `UnifiedAssistantMode`, `PromptInspector`,
  and tests to the runtime owner.
- Delete `AssistantTurnExecutor.injectProjectMemoryInstruction(...)`,
  `AssistantTurnExecutor.injectTaskContractInstruction(...)`, and
  `AssistantTurnExecutor.injectStaticVerificationRepairInstruction(...)`.

## Non-Goals

- No public CLI/product behavior change.
- No outcome resolver move.
- No SCC/cycle cleanup.
- No `SetupCmd.java` edits.
- No Qodana changes.
- No candidate recut.
- No deprecated executor wrappers.
- No baseline expansion for architecture-boundary failures.

## Invariants

- Preserve project-memory insertion ordering.
- Preserve current-turn capability insertion ordering and idempotence.
- Preserve static-repair prompt insertion ordering.
- Preserve stale repair supersession behavior.
- Preserve `LocalTurnTraceCapture.recordRepair(...)` status and summary
  behavior.
- Keep `AssistantTurnPreparation` package-private.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnPromptInstructionsTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptInspectorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorProjectMemoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.LayeredArchitectureTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
rg "AssistantTurnExecutor\.inject" src/main src/test
rg "AssistantTurnPreparation\.inject" src/main src/test
git diff --check
git status --short -- . ':!site'
```
