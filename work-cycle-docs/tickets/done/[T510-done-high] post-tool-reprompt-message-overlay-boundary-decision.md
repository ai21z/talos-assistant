# [T510-done-high] Post Tool Reprompt Message Overlay Boundary Decision

## Status

Done.

## Scope

T510 reinspects `ToolCallRepromptStage` after T509 extracted
`ToolRepromptMessageOverlay`.

This is a no-code decision ticket. It does not change runtime behavior,
reprompt ordering, prompt wording, transient retry behavior, engine-error
handling, static repair semantics, expected-target progress, approval handling,
protected-path behavior, trace wording, or tool-surface narrowing.

## Source Evidence

Fresh `origin/v0.9.0-beta-dev` after T509 and the beta CI recovery trigger:

| Source | Finding |
| --- | --- |
| `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java` | 425 lines after T509. |
| `ToolCallRepromptStage.reprompt(...)` lines 28-326 | Still owns live continuation sequencing and stop/continue precedence. |
| `ToolCallRepromptStage` lines 94-149 | All-success post-mutation branch mixes P0 skip behavior, static-web verification pass handling, static-web continuation planning, full-rewrite target progress, and expected-target progress. |
| `ToolCallRepromptStage` lines 224-240 | Recomputes static full-rewrite and expected-target remaining targets before choosing the pending action obligation. |
| `ToolCallRepromptStage` lines 247-263 | Applies `ToolRepromptMessageOverlay`, snapshots request messages, and calls the generic reprompt path. |
| `ToolCallRepromptStage.chatReprompt(...)` lines 328-365 | Owns live LLM continuation error handling and exact user-facing wording for context budget, connection failure, missing model, generic engine error, and generic exceptions. |
| `ToolCallRepromptStage.chatRepromptResult(...)` lines 367-394 | Owns the actual `LlmClient.chatFull(...)` call plus empty-answer fallback and pending-obligation failure handling. |
| `ToolCallRepromptStage.hasStaticRepairContext(...)` lines 401-403 | Checks for full-write repair context by reparsing rendered `RepairPolicy` context. |
| `ToolCallRepromptStage.remainingFullRewriteRepairTargets(...)` lines 405-422 | Builds required full-write repair targets from repair context plus `state.staticWebFullRewriteRequiredTargets`, subtracts successfully mutated normalized path hints, sorts the remainder, and returns the remaining targets. |
| `src/main/java/dev/talos/runtime/repair/RepairPolicy.java` lines 492-510 | Owns parsing `Full-file replacement targets:` from rendered static repair context. |
| `src/test/java/dev/talos/core/llm/ToolCallRepromptStageToolSurfaceTest.java` | Already covers static full-rewrite repair tool narrowing and compact static-repair payload behavior. |
| `src/test/java/dev/talos/runtime/toolcall/ToolCallRepromptStageTest.java` | Contains ownership tests proving previous extractions moved out of the stage. |

## Candidate Assessment

### Post-Mutation Continuation Selection

Do not extract this next.

The all-success mutation branch is not a single policy owner. It combines:

- static-web verifier-pass short-circuit;
- P0 skip after all-success mutation;
- static-web creation continuation;
- static full-rewrite repair target progress;
- expected-target mutation progress;
- pending action obligation state;
- exact debug wording.

Moving this now would likely create a broad "continuation manager" that hides
the actual ordering rather than clarifying ownership. It should stay in the
stage until a narrower owner emerges.

### Chat Reprompt Execution

Do not extract this next.

`chatReprompt(...)` and `chatRepromptResult(...)` are live IO boundaries. They
own:

- `LlmClient.chatFull(...)`;
- exact connection, model-not-found, engine-error, and generic-exception
  wording;
- context-budget fallback routing;
- no-answer fallback;
- pending-obligation failure after no executable calls;
- the T509-sensitive transient retry snapshot path in the generic overlay
  branch.

This can become an owner later, but it needs a dedicated error-wording and
transient-retry regression packet. It is too risky as the immediate next slice.

### Static Full-Rewrite Repair Target Accounting

This is the next coherent implementation boundary.

The remaining target calculation is deterministic, repeated, and conceptually
separate from the reprompt-stage choreography:

- collect required full-write targets from rendered repair context;
- include runtime-owned `state.staticWebFullRewriteRequiredTargets`;
- normalize required targets;
- collect successful mutating path hints from `state.toolOutcomes`;
- subtract already-mutated targets;
- return sorted remaining targets;
- expose whether a static repair context exists without making the stage parse
  rendered repair text directly.

This owner should not render prompts, choose tools, perform an LLM call, change
pending obligation wording, or decide whether the loop stops.

## Decision

The next implementation ticket should be:

```text
[T511] Extract static full-rewrite repair target accounting
```

Recommended owner:

```text
dev.talos.runtime.toolcall.StaticRepairTargetProgressAccounting
```

Recommended responsibility:

- `hasStaticRepairContext(LoopState state)`;
- `remainingFullRewriteRepairTargets(LoopState state)`;
- no side effects;
- no prompt rendering;
- no tool-surface decisions;
- no chat/LLM execution;
- preserve current sorting, normalization, duplicate handling, and null
  handling.

`ToolCallRepromptStage` should continue to own:

- approval-denial and path-policy stop order;
- expected-target scope repair ordering;
- terminal read-only answer selection;
- all-success and partial-success mutation continuation sequencing;
- pending action obligation selection;
- tool-surface selection through `ToolRepromptRequestBuilder`;
- overlay lifecycle through `ToolRepromptMessageOverlay`;
- chat reprompt execution and exact error wording.

## T511 Test Shape

Start with RED ownership tests for the new owner:

- `StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(...)`
  returns context full-write targets that have not yet been successfully
  mutated.
- It includes `state.staticWebFullRewriteRequiredTargets` even when rendered
  repair context is absent.
- It normalizes successful mutation path hints before subtracting them.
- It ignores failed or read-only tool outcomes.
- It returns sorted remaining paths.
- `hasStaticRepairContext(...)` returns true only when rendered static repair
  context contains full-write targets.
- `ToolCallRepromptStage` no longer contains the private
  `remainingFullRewriteRepairTargets(...)` or `hasStaticRepairContext(...)`
  helpers and delegates to the new owner.

Focused verification should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticRepairTargetProgressAccountingTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.core.llm.ToolCallRepromptStageToolSurfaceTest" --no-daemon
```

Full gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Do Not Touch In T511

T511 must not move:

- `chatReprompt(...)`;
- `chatRepromptResult(...)`;
- transient retry behavior;
- connection/model-not-found/generic engine-error wording;
- post-mutation continuation ordering;
- `StaticWebContinuationPlanner`;
- `ExpectedTargetProgressAccounting`;
- `ToolRepromptRequestBuilder`;
- `ToolRepromptMessageOverlay`;
- pending action obligation wording or precedence;
- static-web diagnostic movement.

## Next Move

Start T511 from fresh `origin/v0.9.0-beta-dev` and extract only
`StaticRepairTargetProgressAccounting`.
