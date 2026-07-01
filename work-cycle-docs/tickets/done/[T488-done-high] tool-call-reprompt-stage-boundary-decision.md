# [T488-done-high] ToolCallRepromptStage Boundary Decision

## Status

Done.

## Scope

T488 inspects `ToolCallRepromptStage` after the execution-stage lane was
closed by T487 and decides the next implementation slice.

This is a no-code decision ticket. It does not change runtime behavior,
approval behavior, protected-read behavior, tool execution, repair behavior,
trace wording, prompt wording, outcome wording, or final-answer behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `511c9f8c`.

| Item | Measurement |
|---|---:|
| `ToolCallRepromptStage.java` | 1007 lines |
| `ToolCallExecutionStage.java` | 493 lines |
| Architecture baseline | 0 |

## Source Findings

`ToolCallRepromptStage` is now the largest remaining runtime tool-loop owner.
It currently contains several different responsibilities:

- top-level reprompt stop/continue orchestration;
- approval-denied and policy-denied stop handling;
- expected-target scope repair continuation;
- static-web continuation orchestration;
- repair/read-only budget enforcement;
- compact mutation continuation after read-only budget or context budget;
- failure-policy stop message rendering;
- source-evidence exact compact repair continuation;
- append-line and old-string compact repair continuation;
- stale/empty edit repair prompt insertion;
- static-repair and expected-target progress prompt insertion;
- native tool-spec selection/narrowing;
- static-repair reprompt message construction;
- chat reprompt execution and engine exception handling;
- current native tool-spec lookup;
- context-budget fallback handling;
- remaining static-repair/expected-target progress accounting.

Some of these already delegate to extracted planners, but the stage still owns
request construction and transport mechanics directly.

## Decision

The next implementation ticket should extract reprompt request assembly, not
continuation policy.

Recommended next ticket:

```text
[T489] Extract tool reprompt request builder
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolRepromptRequestBuilder
```

Preferred responsibilities:

- build the reprompt tool-spec list from current native specs;
- narrow tools to `talos.write_file` for active static-repair progress;
- narrow tools to `talos.write_file` / `talos.edit_file` for active expected
  target progress;
- build static-repair reprompt messages while preserving the current wording;
- enrich static verification repair context with selector facts via
  `RepairPolicy.enrichSelectorFactsForRepairContext(...)`;
- build required-tool-choice controls for active pending action obligations;
- keep debug tags exactly as today.

`ToolCallRepromptStage` should keep:

- deciding whether a reprompt is needed;
- pending obligation state mutations;
- adding/removing temporary system messages around the request;
- invoking the LLM;
- engine exception handling;
- context-budget fallback policy;
- compact mutation continuation policy;
- failure-policy stop behavior.

## Why This Is The Correct Slice

Request assembly is a coherent infrastructure boundary. It does not decide
whether the loop should continue, what repair is needed, or how failures are
reported. It only turns the current loop state and obligation flags into the
messages, tool specs, and controls passed to the LLM.

That boundary is safer and clearer than moving continuation policy first.
Continuation policy mixes state transitions, trace records, pending action
obligations, compact fallbacks, and final stop answers.

## Rejected Immediate Work

### Extract Context-Budget Handling

Rejected for T489.

`stopAfterContextBudgetExceeded(...)` and
`tryCompactMutationContinuation(...)` mix trace warnings, pending action
obligation failure, compact mutation continuation, read-only evidence fallback,
failure decisions, deterministic final answers, and LLM calls. It is a real
future candidate, but it should not be the first reprompt-stage extraction.

### Extract Failure-Policy Stop Rendering

Rejected for T489.

`failurePolicyStopMessage(...)` is smaller and relatively pure, but it is not
the primary ownership confusion inside the stage. It can be revisited after
request assembly is extracted.

### Extract Expected-Target Progress Accounting

Rejected for T489.

`remainingExpectedMutationTargets(...)` touches task contracts, path effects,
workspace-operation plans, path normalization, basename fallback matching, and
static-repair exclusion. It is important, but it needs a focused decision if
we move it.

## Required T489 Tests

Start with RED tests for `ToolRepromptRequestBuilder`:

- static-repair progress narrows tools to `talos.write_file` when available;
- expected-target progress narrows tools to `talos.write_file` and
  `talos.edit_file` when available;
- when narrowing would remove every tool, the original tool list is preserved;
- static-repair reprompt messages preserve current system/user wording and
  include the enriched repair context when present;
- pending action obligations produce required-tool-choice controls only when
  the current model supports required tool choice and mutating tools are
  present;
- `ToolCallRepromptStage` delegates request assembly and no longer owns
  `repromptToolSpecs(...)`, `repromptMessages(...)`, `repromptControls(...)`,
  `currentNativeToolSpecs(...)`, or `filterTools(...)`.

Recommended focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolRepromptRequestBuilderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --tests "dev.talos.runtime.ToolCallLoopTest.*static*" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
