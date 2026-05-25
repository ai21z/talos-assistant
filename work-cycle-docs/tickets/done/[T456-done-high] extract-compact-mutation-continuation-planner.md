# [T456-done-high] Extract Compact Mutation Continuation Planner

## Status

Done.

## Scope

T456 implements the T455 decision: extract compact mutation continuation
planning from `ToolCallRepromptStage` into a plan-returning runtime/toolcall
owner.

This is an ownership refactor. It preserves runtime behavior, prompt wording,
tool selection, context-budget handling, trace wording, action-obligation
records, failure dominance, and final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `972ea2b2`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` after extraction | 1709 lines |
| `CompactMutationContinuationPlanner.java` | 407 lines |
| `CompactMutationContinuationPlannerTest.java` | 212 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.CompactMutationContinuationPlanner
```

The planner now owns compact mutation continuation planning:

- compact mutation continuation eligibility;
- read-only-progress-only gate;
- workspace-operation exclusion;
- expected mutation target selection;
- repair-context target precedence;
- write/edit tool narrowing;
- compact write/edit schema rewriting;
- required-tool controls;
- compact continuation request messages;
- expected-target frame;
- static-web coherence guidance;
- current readback evidence;
- source-derived exact evidence readbacks;
- sensitive readback path exclusion;
- similar sibling readback inclusion for traps such as `script.js` versus
  `scripts.js`;
- compact readback truncation.

`ToolCallRepromptStage` still owns live loop lifecycle:

- deciding when compact mutation continuation is attempted;
- invoking `state.ctx.llm().chatFull(...)`;
- writing `LoopState.currentText`;
- writing `LoopState.currentNativeCalls`;
- recording `COMPACT_MUTATION_CONTINUATION` trace events;
- recording `RETRIED_COMPACT_CONTEXT` action-obligation events;
- preserving no-tool deterministic failure behavior;
- preserving context-budget and engine-exception fallback;
- preserving continuation versus terminal-stop decisions.

## Behavior Preserved

Preserved:

- exact `[CompactMutationContinuation]` prompt marker;
- exact `compact-mutation-continuation` debug tag;
- required tool-choice behavior when supported;
- write-file-only narrowing for static repair contexts;
- write/edit narrowing otherwise;
- compact write/edit schema rewrite wording;
- no compact mutation continuation after mutation progress;
- no compact mutation continuation when a pending action obligation exists;
- source-derived evidence phrase frame;
- similar sibling readback frame;
- sensitive readback exclusion;
- no-tool deterministic failure wording;
- context-budget failure dominance when compact continuation cannot proceed.

Not changed:

- expected-target scope repair;
- source-evidence exact repair;
- append-line compact repair;
- old-string compact repair;
- static-web continuation planning;
- compact read-only evidence continuation;
- terminal read-only stop answers;
- generic `chatReprompt(...)` provider lifecycle;
- final answer wording.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: CompactMutationContinuationPlanner
```

GREEN focused planner verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
```

Focused compact-mutation regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationKeepsStaticWebGuidanceOutOfNonWebCompactPrompt" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationCompactRetryNoToolRemainsFailureDominant" --no-daemon
```

Adjacent stage and overinspection regressions passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.CompactMutationContinuationPlannerTest" --no-daemon
```

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.

## Next Move

After T456 is merged and beta push CI is clean, inspect the post-T456
`ToolCallRepromptStage` shape before choosing T457. Do not assume expected
target scope repair, source-evidence exact repair, append-line repair, or
old-string repair is automatically next.
