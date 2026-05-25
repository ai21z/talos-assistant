# [T433-done-high] AssistantTurnExecutor Retry Orchestration Boundary Decision

## Status

Done.

## Scope

T433 inspects retry orchestration in `AssistantTurnExecutor` after the
answer-shaping guard lane was closed by T432.

This is a no-code decision ticket. It does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `41771182`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4815 lines |
| Architecture baseline | 0 |

## Source Inventory

`AssistantTurnExecutor` currently owns these retry paths:

| Retry path | Source | Shape |
|---|---|---|
| Read-only inspection retry | `readOnlyInspectionRetryIfNeeded(...)` | Builds a retry prompt, calls `chatFull(...)`, may re-enter `ToolCallLoop`, returns retry loop evidence. |
| Post-tool synthesis retry | `synthesisRetryIfNeeded(...)` | If tools were used and the answer is a deflection, appends a focused retry prompt, calls `chatFull(...)`, returns replacement text only. |
| Missing-mutation retry | `mutationRequestRetryIfNeeded(...)` | Builds compact mutation retry frames, narrows tool specs, records action obligations, may re-enter `ToolCallLoop`, merges mutation/evidence results. |
| Inspect-completeness retry | `inspectCompletenessRetryIfNeeded(...)` | Computes missing primary reads, builds retry prompt, calls `chatFull(...)`, may re-enter `ToolCallLoop`, merges read-only retry evidence. |
| No-tool grounding retry | `groundingRetryIfNeeded(...)` | Mutates messages, calls `chatFull(...)`, returns retry text or an ungrounded annotation. |

These are not one owner.

## Ownership Findings

### Missing-mutation retry

Do not move next.

It is policy-dense and high impact:

- action-obligation recording;
- compact retry prompt construction;
- retry tool-surface narrowing;
- conditional review/fix handling;
- static repair wrong-tool handling;
- denied/invalid mutation handling;
- retry loop execution;
- post-retry mutation/evidence merging.

This is an execution-control subsystem, not a small extraction.

### Inspect-completeness and read-only inspection retries

Do not move next.

Both can re-enter the tool loop and both interact with evidence completeness,
primary-file heuristics, linked-script evidence, static-web diagnostics, and
read-only workspace inspection. Moving either casually would risk changing when
Talos reads, retries, or grounds static-web answers.

### No-tool grounding retry

Do not move next.

T428 and T432 already recorded the critical fact: the pure no-tool answer guard
has been extracted, but this method is not pure rendering. It mutates messages,
calls the LLM, and branches on retry output. It belongs in retry orchestration,
not `dev.talos.runtime.outcome`.

### Post-tool synthesis retry

This is the only small coherent implementation candidate.

It has one purpose: when the model used tools but ended with a deflection, make
one focused non-streaming synthesis attempt anchored to the original user
request and the already gathered tool evidence.

It does not:

- narrow tool specs;
- re-enter the tool loop;
- execute workspace tools;
- change mutation policy;
- merge retry evidence;
- change outcome dominance.

The extraction should stay in CLI turn-orchestration ownership because it calls
the model and mutates turn messages. It should not move into runtime outcome
ownership.

## Decision

The next implementation ticket should be:

```text
[T434] Extract post-tool synthesis retry
```

Target owner:

```text
dev.talos.cli.modes.PostToolSynthesisRetry
```

T434 should move only:

- deflection detection used by post-tool synthesis retry;
- the retry prompt construction;
- the one-shot retry orchestration that appends assistant/user retry messages
  and calls a supplied chat function.

`AssistantTurnExecutor` should keep compatibility wrappers for existing tests:

- `isDeflection(...)`;
- `synthesisRetryIfNeeded(...)`.

The new class should not call `ctx.llm()` directly. It should receive a small
chat function from `AssistantTurnExecutor` so provider controls and tool-surface
selection remain owned by the existing `chatFull(...)` path.

## T434 Guardrails

T434 must preserve:

- exact retry prompt wording;
- original request anchoring and truncation behavior;
- message append order;
- null/blank/deflection behavior;
- logging posture;
- no-tool and mutation retry behavior;
- inspect-completeness and read-only inspection retries;
- streaming branch behavior.

T434 must not move:

- missing-mutation retry;
- read-only inspection retry;
- inspect-completeness retry;
- no-tool grounding retry;
- `chatFull(...)` provider-control construction;
- static-web answer overrides;
- outcome dominance policy.

## Verification For This Ticket

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
