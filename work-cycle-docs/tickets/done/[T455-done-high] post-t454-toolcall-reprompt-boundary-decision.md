# [T455-done-high] Post-T454 ToolCallRepromptStage Boundary Decision

## Status

Done.

## Scope

T455 reinspects the post-T454 `ToolCallRepromptStage` shape after
`StaticWebContinuationPlanner` was extracted.

This is a no-code decision ticket. It does not change runtime behavior,
prompt wording, tool selection, verifier behavior, failure dominance,
context-budget behavior, mutation repair semantics, or final outcome rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `4a6acb86`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| Java version | `javaVersion=21` |
| `ToolCallRepromptStage.java` | 1987 lines |
| `StaticWebContinuationPlanner.java` | 545 lines |
| Architecture baseline | 0 |

## Post-T454 Source Shape

T454 proved a useful pattern: move a coherent continuation planner out of
`ToolCallRepromptStage`, but keep live loop placement and backend invocation in
the stage.

`StaticWebContinuationPlanner` now owns static-web continuation planning:

- directory-only static-web creation plans;
- static verification failure continuation plans;
- missing static-web target inference;
- linked CSS/JavaScript asset inference from mutated HTML;
- small-web target satisfaction accounting;
- continuation messages, narrowed tools, controls, retry names, and optional
  pending-action obligation details.

`ToolCallRepromptStage` correctly still owns:

- top-level reprompt ordering;
- applying pending action obligations;
- invoking `chatReprompt(...)`;
- mutating `LoopState.currentText` and `LoopState.currentNativeCalls`;
- context-budget fallback routing;
- failure dominance and terminal stop behavior.

## Remaining Large Areas

The remaining `ToolCallRepromptStage` responsibilities are not equally good
implementation targets.

| Area | Current source evidence | Decision |
|---|---|---|
| Compact mutation continuation | `tryCompactMutationContinuation(...)`, `compactMutationContinuationForContextBudget(...)`, `compactMutationContinuationMessages(...)`, target/readback/source-evidence helpers, tool narrowing, required-tool controls, sensitive-path filtering, similar-sibling readback detection. | Best next implementation owner, but only as a plan-returning extraction. Keep backend call and loop-state mutation in the stage. |
| Expected-target scope repair | `nextExpectedTargetScopeRepair(...)`, failure-reason parsing, expected-target fallback extraction, static-web mutation readbacks, exact replacement repair call, pending repair keys. | Coherent but riskier. It mixes path-policy failure wording, exact-edit repair, static-web context, and remaining expected-target calculation. Do not choose it before compact mutation planning. |
| Source-evidence exact repair | `nextSourceEvidenceExactRepair(...)`, source readback extraction, write-file schema narrowing, exact evidence phrase framing. | Later candidate. It depends on remaining expected-target calculation and source-derived evidence rules, so it should not be the immediate next extraction. |
| Append-line and old-string compact repairs | `nextAppendLineCompactRepair(...)`, `nextOldStringMissCompactRepair(...)`, repair-specific messages, readback selection. | Later candidates. They are repair-lane specific and should not be mixed with compact mutation continuation. |
| Generic `chatReprompt(...)` | Provider call, engine-error wording, context-budget fallback, and `LoopState` mutation. | Keep in `ToolCallRepromptStage`. Moving it now would mix generic provider lifecycle with one continuation owner. |
| Top-level `reprompt(...)` ordering | Approval denial, expected-target repair, terminal read-only stop, mutation success, static-web continuation, failure policy, context-budget stop, and cleanup. | Keep in `ToolCallRepromptStage`. This is orchestration, not a clean extracted policy yet. |

## Why T445 And T449 Rejected Compact Mutation Continuation

T445 and T449 rejected extracting compact mutation continuation because the
surface was not just prompt text. At that point it owned:

- loop progression;
- pending action-obligation state;
- mutation/read-only counters;
- readback freshness;
- static repair context;
- source-derived evidence;
- sensitive-path filtering;
- failure-decision mutation;
- provider retry behavior;
- continuation versus terminal stop behavior.

That rejection was correct at the time.

## What Changed After T454

T454 did not make compact mutation continuation simple. It did prove the safer
extraction style for this file:

```text
planner returns messages/tools/controls;
ToolCallRepromptStage keeps lifecycle placement and provider calls.
```

That same split is now the right next shape for compact mutation continuation.
The next owner should not run the backend and should not write loop state. It
should only decide whether a compact mutation continuation plan exists and, if
so, return the exact request frame the stage already sends today.

## Decision

The next implementation ticket should be:

```text
[T456] Extract compact mutation continuation planner
```

Target owner:

```text
dev.talos.runtime.toolcall.CompactMutationContinuationPlanner
```

Preferred shape:

```text
CompactMutationContinuationPlanner.planForContextBudget(
    LoopState state,
    List<ToolSpec> baseTools,
    String retryName
)
```

The returned plan should contain only:

- request messages;
- narrowed `ToolSpec` list;
- `ChatRequestControls`.

`ToolCallRepromptStage` should keep:

- `tryCompactMutationContinuation(...)` lifecycle placement;
- `state.ctx.llm().chatFull(...)`;
- `LoopState.currentText` and `LoopState.currentNativeCalls` mutation;
- no-tool deterministic failure handling;
- trace warnings and action-obligation records;
- context-budget exception fallback;
- generic engine exception fallback;
- failure dominance and loop continuation decisions.

## T456 Guardrails

T456 must preserve:

- exact `[CompactMutationContinuation]` prompt wording;
- exact `compact-mutation-continuation` debug tag;
- required tool-choice behavior when the backend supports required tools;
- `talos.write_file` and `talos.edit_file` schema rewrites;
- write-file-only narrowing when static repair context is present;
- write/edit narrowing otherwise;
- workspace-operation exclusion;
- no compact continuation after a mutation has already succeeded;
- no compact continuation when a pending action obligation exists;
- read-only-progress-only eligibility;
- expected target selection from repair context before task contract targets;
- static-web coherence guidance for expected web targets;
- source-derived evidence exact-phrase framing and source readbacks;
- sensitive readback path exclusion for `.env`, `.git`, `.ssh`, `.gnupg`,
  `id_rsa`, `credentials`, and `secret`;
- similar sibling readback inclusion for traps such as `script.js` versus
  `scripts.js`;
- readback truncation text and limit;
- no-tool deterministic failure behavior;
- `COMPACT_MUTATION_CONTINUATION`, `COMPACT_MUTATION_CONTINUATION_FAILED`,
  and `COMPACT_MUTATION_CONTINUATION_CONTEXT_BUDGET_EXCEEDED` trace behavior.

T456 must not touch:

- expected-target scope repair;
- source-evidence exact repair;
- append-line compact repair;
- old-string compact repair;
- static-web continuation planning;
- compact read-only evidence continuation;
- terminal read-only stop answers;
- `chatReprompt(...)` generic provider lifecycle;
- failure policy ordering;
- `AssistantTurnExecutor`;
- final answer wording.

## Rejected T456 Alternatives

### Extract expected-target scope repair first

Rejected.

It is a coherent cluster, but it is not the next safest owner. It mixes
expected-target scope failure parsing, path-policy wording, static-web readback
collection, exact replacement repair calls, pending repair keys, and remaining
expected target calculation.

### Extract source-evidence exact repair first

Rejected.

The source-evidence repair lane is important, but it depends on remaining
expected target calculation and source-derived evidence semantics. It is a
better later implementation ticket after compact mutation planning has been
separated.

### Move `chatReprompt(...)`

Rejected.

`chatReprompt(...)` is generic provider lifecycle: backend call, context-budget
fallback routing, engine-error wording, and loop-state mutation. Moving it
would create a larger behavior refactor with weak ownership payoff.

### Extract only compact prompt string construction

Rejected.

That would leave tool narrowing, target/readback selection, source evidence,
required-tool controls, and eligibility in the stage. The right owner is the
whole plan, not only the prompt text.

## Proposed T456 Test Plan

Start with a RED planner ownership test for:

- compact mutation continuation plan creation after read-only progress;
- expected target frame preservation;
- compact mutation tool narrowing/schema rewrite;
- source evidence readback inclusion;
- sensitive readback exclusion;
- similar sibling readback inclusion.

Focused regression tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationKeepsStaticWebGuidanceOutOfNonWebCompactPrompt" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationCompactRetryNoToolRemainsFailureDominant" --no-daemon
```

Adjacent regression tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.singleTargetMutationReadOnlyOverInspectionUsesCompactMutationContinuation" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
```

Full gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required no-code closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
