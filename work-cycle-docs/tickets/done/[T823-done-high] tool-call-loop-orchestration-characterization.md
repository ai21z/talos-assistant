# T823 ToolCallLoop Orchestration Characterization

Status: done
Priority: high
Wave: 5
Owner: architecture/runtime tool-call loop

## Summary

Characterize `runtime.ToolCallLoop` orchestration before any production
extraction into a future package-private `ToolCallLoopEngine`.

T823 is characterization-only. It pins the loop's public result shape and
observable ordering so T824 can move orchestration deliberately rather than
moving parse, execute, reprompt, iteration-limit, and finalization behavior
blindly.

## Evidence Identity

- Branch: `v0.9.0-beta-dev`
- Pre-T823 HEAD:
  `33e3998bdb2dcea74a0bbdde7dcf8c3b59626f23`
- Talos version: `0.10.5`
- Prior milestone:
  `work-cycle-docs/tickets/done/[T822-done-high] rag-tool-protocol-text-cycle-break.md`
- Characterization report:
  `work-cycle-docs/reports/t823-tool-call-loop-orchestration-characterization.md`

The priority index remains review-order evidence only. T823 succeeds by
capturing current behavior and move/stay boundaries, not by reaching a specific
generated architecture score.

## Scope

- Add focused characterization tests for `ToolCallLoop.run(...)`.
- Record the loop orchestration source anchors and future extraction boundary.
- Keep production source unchanged.

T823 characterization targets:

- no-tool path returns a zeroed `LoopResult` and does not invent tool
  execution;
- text tool-call path preserves parse, execute, reprompt, and finalization
  order;
- native tool-call path seeds the loop from `NativeToolCall` and preserves the
  public result shape;
- iteration-limit path sets `hitIterLimit` and applies the finalizer notice;
- report-shape test pins the T824 move/stay boundary.

## Non-Goals

- No production extraction.
- No `ToolCallLoopEngine` implementation.
- No public `ToolCallLoop` API change.
- No `LoopResult` or `ToolOutcome` move.
- No `ToolCallSupport`, `ToolCallExecutionStage`, `LoopState`, or
  `ExecutionOutcome` move.
- No package-cycle cleanup.
- No Qodana changes.
- No candidate recut.

## T824 Candidate Boundary

Candidate owner: package-private `ToolCallLoopEngine` in
`dev.talos.runtime`.

Move later in T824:

- body of `ToolCallLoop.run(..., List<NativeToolCall>, ...)`;
- loop state creation;
- parse, execute, and reprompt stage orchestration;
- iteration-limit handling;
- finalization call ordering;
- alias-rescue cushion calculation;
- `LoopResult` construction.

Keep in `ToolCallLoop` until separately scoped:

- public constructors;
- public `run(...)` overloads;
- `DEFAULT_MAX_ITERATIONS`;
- public nested `LoopResult`;
- public nested `ToolOutcome`;
- existing static helper delegates;
- `ToolCallSupport`;
- `ToolCallExecutionStage`;
- `LoopState`;
- `ExecutionOutcome`;
- tool model types.

## Acceptance Criteria

- `ToolCallLoopOrchestrationCharacterizationTest` exists.
- The T823 report names `ToolCallLoopEngine` and the T824 move/stay boundary.
- The characterization test suite covers all T823 targets listed in scope.
- `ToolCallLoopTest`, `ToolCallLoopP0Test`, `ToolCallLoopNativeTest`,
  `ToolCallLoopCompactionTest`, `dev.talos.runtime.toolcall.*`, full `check`,
  and `wikiEvidenceCloseGate --rerun-tasks` remain green.
- No production source files are changed by T823.

## Completion Evidence

T823 is done. It added characterization-only coverage for
`runtime.ToolCallLoop` orchestration and did not change production source.

Completion evidence:

- `ToolCallLoopOrchestrationCharacterizationTest` covers no-tool, text
  tool-call, native tool-call, iteration-limit, and report-boundary behavior.
- The T823 report records the future T824 owner as package-private
  `dev.talos.runtime.ToolCallLoopEngine`.
- T823 made no production source changes and did not authorize extraction.
- Focused `ToolCallLoop` suites, `dev.talos.runtime.toolcall.*`, full `check`,
  and `wikiEvidenceCloseGate --rerun-tasks` were green before closeout.
- The T824 package correction is deliberate: Java package-private access does
  not cross subpackages, and placing the engine in `dev.talos.runtime` avoids
  broadening `ToolLoopFinalAnswerFinalizer` visibility.

The next Wave 5 move is T824, a behavior-preserving extraction into
package-private `ToolCallLoopEngine`.

## Verification

Run:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopOrchestrationCharacterizationTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopP0Test" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopNativeTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopCompactionTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check
git status --short -- . ':!site'
```
