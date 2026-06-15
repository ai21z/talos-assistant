# T824 ToolCallLoopEngine Extraction

Status: open
Priority: high
Wave: 5
Owner: architecture/runtime tool-call loop

## Summary

Extract `runtime.ToolCallLoop` orchestration into package-private
`dev.talos.runtime.ToolCallLoopEngine` while preserving `ToolCallLoop` as the
public facade.

T824 is behavior-preserving architecture work. It follows T823
characterization and should not change public CLI behavior or the public Java
surface of `ToolCallLoop`.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T824 HEAD:
  `e9ae1a41ff591f0b165dd5e83fdb352a1fb74f75`
- Talos version: `0.10.5`
- Prior characterization:
  `work-cycle-docs/tickets/done/[T823-done-high] tool-call-loop-orchestration-characterization.md`

## Scope

- Add package-private `dev.talos.runtime.ToolCallLoopEngine`.
- Move only the body of
  `ToolCallLoop.run(String, List<NativeToolCall>, List<ChatMessage>, Path, RuntimeTurnContext)`
  into the engine.
- Keep `ToolCallLoop` as the public facade.
- Keep `ToolLoopFinalAnswerFinalizer` package-private.
- Keep all public `ToolCallLoop` constructors, `run(...)` overloads,
  `DEFAULT_MAX_ITERATIONS`, `LoopResult`, `ToolOutcome`, and static helper
  delegates stable.

## Non-Goals

- No public CLI/product behavior change.
- No public Java API change.
- No `LoopResult` or `ToolOutcome` move.
- No `LoopState`, `ToolCallSupport`, `ToolCallExecutionStage`,
  `ToolCallParseStage`, `ToolCallRepromptStage`, `ExecutionOutcome`, or tool
  model type move.
- No Qodana changes.
- No candidate recut.
- No `SetupCmd.java` edits.

## Implementation Notes

- `ToolCallLoopEngine` lives in `dev.talos.runtime`, not
  `dev.talos.runtime.toolcall`, because Java package-private access does not
  cross subpackages.
- The engine is intentionally package-private.
- The facade owns public compatibility; the engine owns orchestration order.
- If characterization tests fail, preserve current behavior by adjusting the
  extraction rather than changing runtime semantics.

## Acceptance Criteria

- `ToolCallLoopEngine` exists and is not public.
- `ToolCallLoop.run(...)`, constructors, `LoopResult`, `ToolOutcome`, and
  static helper delegates remain public-compatible.
- T823 characterization and existing `ToolCallLoop` suites pass.
- `validateArchitectureBoundaries`, `LayeredArchitectureTest`, full `check`,
  and `wikiEvidenceCloseGate --rerun-tasks` pass.
- Source hygiene confirms no public `ToolCallLoopEngine`.
- `site/` remains untouched and unstaged.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopOrchestrationCharacterizationTest" --rerun-tasks --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopP0Test" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopNativeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopCompactionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.LayeredArchitectureTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
rg "public .*ToolCallLoopEngine" src/main/java src/test/java
rg "class ToolCallLoopEngine" src/main/java/dev/talos/runtime
git diff --check
git status --short -- . ':!site'
```
