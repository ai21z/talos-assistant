# [T445-done-high] Context-Budget Continuation Boundary Decision

## Status

Done.

## Scope

T445 inspects the context-budget continuation surface selected by the T444
retry-orchestration closeout.

This is a no-code inspection and decision ticket. It does not change runtime
behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `08db577f`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 3572 lines |
| `ToolCallRepromptStage.java` | 2730 lines |
| `ResponseObligationVerifier.java` | 146 lines |
| Architecture baseline | 0 |

## Source Inventory

The current context-budget continuation behavior has three distinct lifecycle
positions.

| Area | Source | Lifecycle | Ownership finding |
|---|---|---|---|
| Current-turn exact-write fallback | `AssistantTurnExecutor.chatStreamFullWithInitialContextFallback(...)`, `chatFullExactWriteContextFallback(...)`, `exactWriteContextFallback(...)`, `compactExactWriteFallbackPlan(...)`, `compactExactWriteFallbackMessages(...)`, `recordExactWriteContextFallback(...)` | Initial full turn exceeds context before the ordinary backend call can complete. | Clean next implementation owner. It is CLI turn fallback construction and can be extracted without moving loop-control semantics. |
| Compact mutation continuation | `ToolCallRepromptStage.stopAfterContextBudgetExceeded(...)`, `tryCompactMutationContinuation(...)`, `compactMutationContinuationForContextBudget(...)`, `compactMutationContinuationMessages(...)`, readback helpers | Tool-loop reprompt exceeds context after read-only progress toward a mutation. | Keep in `ToolCallRepromptStage` for now. It depends on `LoopState`, pending obligations, readbacks, static repair context, source-derived evidence, and loop continuation state. |
| Compact read-only evidence continuation | `ToolCallRepromptStage.tryCompactReadOnlyEvidenceContinuation(...)`, `readOnlyEvidenceAnswerForCompactFallback(...)`, `readOnlyEvidenceAnswerMessages(...)` | Tool-loop read-only answer synthesis exceeds context after successful target readback. | Keep in `ToolCallRepromptStage` for now. It depends on read-only loop state, target readback selection, and terminal loop failure dominance. |

`ResponseObligationVerifier.deterministicContextBudgetRetrySkippedAnswer(...)`
and `contextBudgetRetrySkippedDetail(...)` are shared wording helpers. They are
not the owner of continuation behavior. They should stay as runtime policy
wording until a later outcome/status model decision proves otherwise.

## Existing Coverage

The exact-write fallback already has focused executor coverage:

- `AssistantTurnExecutorTest.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt(...)`
- `AssistantTurnExecutorTest.contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation(...)`

Those tests assert the important behavior:

- stale older static repair history is omitted;
- compact current-turn prompt reaches the backend;
- prompt includes expected targets and exact literal content;
- native tool surface is narrowed to `talos.write_file`;
- required tool choice is preserved when supported;
- trace records `RETRIED_COMPACT_CONTEXT`;
- deictic/non-literal mutation requests do not use this fallback.

The `ToolCallRepromptStage` compact continuation paths also have focused
coverage, including:

- `ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress(...)`
- `ToolCallLoopTest.oldStringMissWithReadbackUsesCompactTargetOnlyRepairBeforeContextBudgetFailure(...)`
- `ToolCallLoopTest.readBeforeEditOldStringMissUsesCompactRepairBeforeContextBudgetFailure(...)`
- `ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure(...)`
- `ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant(...)`

That coverage is broad enough to protect behavior, but it also shows why the
tool-loop compact continuation code is not the next simple extraction. It is
entangled with loop state and failure dominance, not just prompt formatting.

## Decision

The next implementation ticket should extract only the current-turn exact-write
context-budget fallback from `AssistantTurnExecutor`.

Target owner:

```text
dev.talos.cli.modes.ExactWriteContextFallback
```

The owner should remain in CLI mode ownership because it prepares a new backend
request for the current turn. It should not move into runtime policy or runtime
outcome packages.

T446 should move only:

- the compact exact-write fallback request value;
- exact-literal fallback eligibility checks;
- compact fallback plan construction;
- compact fallback message construction;
- trace recording for `CONTEXT_BUDGET_CURRENT_TURN_FALLBACK`;
- debug-tag attachment for `context-budget-current-turn-fallback`;
- write-file-only tool narrowing needed by this fallback.

`AssistantTurnExecutor` should keep the lifecycle placement:

- catch `EngineException.ContextBudgetExceeded`;
- ask the fallback owner whether a compact request exists;
- call the existing `ctx.llm().chatStreamFull(...)` or `ctx.llm().chatFull(...)`
  with the prepared compact request;
- throw the original budget exception when no fallback is applicable.

## Rejected T446 Alternatives

### Extract `ToolCallRepromptStage` compact mutation continuation now

Rejected.

It is not a simple prompt owner. It depends on:

- `LoopState`;
- pending action-obligation state;
- mutation counters;
- read-only progress detection;
- static repair context;
- source-derived evidence readbacks;
- readback freshness and sensitive-path filtering;
- failure-decision mutation;
- loop continuation versus terminal answer behavior.

Moving it now would be a behavior refactor, not a hygiene ticket.

### Extract compact read-only evidence continuation now

Rejected.

It is narrower than compact mutation continuation, but it still writes terminal
loop state and preserves context-budget failure dominance when the compact
answer emits tool calls. It should stay with loop-control state until a broader
`ToolCallRepromptStage` boundary decision is made.

### Extract shared compact prompt or tool-spec helpers first

Rejected.

The exact-write fallback, missing-mutation retry, compact mutation
continuation, and read-only evidence continuation all use compact prompts, but
their lifecycle constraints differ. A shared helper first would create generic
abstraction before ownership is clear.

### Move context-budget wording from `ResponseObligationVerifier`

Rejected.

The wording helpers are already small and runtime-owned. Moving them would not
improve continuation ownership.

## T446 Guardrails

T446 must preserve:

- exact prompt wording for the compact exact-write fallback;
- exact fallback eligibility;
- no fallback for deictic/non-literal mutation requests;
- stale-history omission;
- `talos.write_file`-only tool surface;
- provider required-tool controls through the existing control path;
- `context-budget-current-turn-fallback` debug tag;
- `RETRIED_COMPACT_CONTEXT` trace status and warning code;
- streaming and non-streaming fallback behavior;
- original exception behavior when the fallback is not applicable.

T446 must not change:

- `MissingMutationRetry`;
- `ToolCallRepromptStage`;
- compact mutation continuation;
- compact read-only evidence continuation;
- context-budget skipped retry wording;
- final answer wording;
- static repair behavior;
- outcome dominance.

## Proposed T446 Verification

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.contextBudgetFallbackDoesNotRunForDeicticNonLiteralMutation" --no-daemon
```

Broader adjacent checks:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure" --no-daemon
```

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before merge.
