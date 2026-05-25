# [T435-done-high] Remaining Retry Orchestration Boundary Decision

## Status

Done.

## Scope

T435 reinspects `AssistantTurnExecutor` after T434 extracted post-tool
synthesis retry.

This is a no-code decision ticket. It does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `c9214753`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4710 lines |
| Architecture baseline | 0 |

## Current Retry And Handoff Shape

`AssistantTurnExecutor.resolveToolLoopAnswer(...)` now runs these steps:

1. post-tool synthesis retry through `PostToolSynthesisRetry`;
2. missing-mutation retry;
3. inspect-completeness retry;
4. partial read-evidence recovery;
5. final tool-loop answer shaping.

`AssistantTurnExecutor.resolveNoToolAnswer(...)` now runs these steps:

1. malformed protocol fast path;
2. missing-mutation retry;
3. direct read-evidence handoff;
4. read-only inspection retry;
5. final no-tool answer shaping.

The remaining retry/handoff methods are:

| Area | Source | Ownership shape |
|---|---|---|
| Direct read-evidence handoff | `unsupportedCapabilityPreflightIfNeeded(...)`, `readEvidenceHandoffIfNeeded(...)`, `readEvidenceRecoveryForPartialTargetsIfNeeded(...)` | Deterministic tool-loop re-entry using `talos.read_file` for `EvidenceGate` targets. No LLM prompt retry. |
| Read-only inspection retry | `readOnlyInspectionRetryIfNeeded(...)` | Builds a corrective prompt, calls `chatFull(...)`, may run the tool loop if the model emits tool calls. |
| Missing-mutation retry | `mutationRequestRetryIfNeeded(...)` | Narrows mutation tool specs, builds compact retry frames, records action obligations, handles invalid/denied/static-repair cases, may run the tool loop. |
| Inspect-completeness retry | `inspectCompletenessRetryIfNeeded(...)` | Computes missing primary/linked-script reads, builds a corrective prompt, calls `chatFull(...)`, may run the tool loop and merge read evidence. |
| No-tool grounding retry | `groundingRetryIfNeeded(...)` | Mutates messages, calls `chatFull(...)`, returns retry text or an ungrounded annotation. |

## Findings

### The broad retry lane should close

There is no single remaining "retry orchestration" owner worth extracting as a
large unit. The remaining methods mix different policies:

- mutation obligation enforcement;
- read evidence collection;
- workspace inspection completeness;
- no-tool answer grounding;
- static-web linked-script evidence;
- protected and unsupported target handling;
- command verification retry wording.

Extracting a generic retry manager would make ownership worse.

### Missing-mutation retry is not the next implementation slice

`mutationRequestRetryIfNeeded(...)` is still high-risk execution control.

It owns or directly coordinates:

- action-obligation trace recording;
- retry tool-surface narrowing;
- workspace-operation retry tools;
- static repair wrong-tool failure handling;
- invalid mutating argument handling;
- denied mutation handling;
- context-budget failure wording;
- compact retry frame construction;
- retry loop execution and mutation evidence merging.

Moving it before a narrower design would be a behavioral refactor disguised as
cleanup.

### Read-only and inspect-completeness retries are not the next slice

Both paths build model prompts and can re-enter the tool loop. They also depend
on primary-file heuristics, linked-script evidence, static-web inspection, and
task-contract evidence requirements.

They should stay in `AssistantTurnExecutor` until the evidence handoff boundary
is cleaner.

### No-tool grounding retry remains intentionally in turn orchestration

`groundingRetryIfNeeded(...)` is not a pure answer guard. T428, T432, and T433
already recorded why: it mutates messages and calls the model on the
non-streaming no-tool branch.

It should not move into runtime outcome ownership.

### Direct read-evidence handoff is the next coherent owner

The direct read-evidence handoff cluster is different from the model retry
paths.

It does not ask the model to try again. It deterministically constructs
`talos.read_file` tool calls for targets selected by `EvidenceGate`, runs the
existing `ToolCallLoop`, and returns loop evidence.

That makes it a coherent next implementation unit, but it should stay in CLI
turn orchestration ownership because it executes the tool loop through
`Context`. Runtime policy should remain pure:

- `EvidenceGate` selects obligation and targets;
- the new CLI handoff owner executes the deterministic read handoff;
- `AssistantTurnExecutor` composes it into the turn flow.

## Decision

Close the broad retry-orchestration lane.

The next implementation ticket should be:

```text
[T436] Extract read evidence handoff
```

Target owner:

```text
dev.talos.cli.modes.ReadEvidenceHandoff
```

T436 should move only:

- `ReadEvidenceHandoffResult`;
- `unsupportedCapabilityPreflightIfNeeded(...)`;
- `readEvidenceHandoffIfNeeded(...)`;
- `readEvidenceRecoveryForPartialTargetsIfNeeded(...)`;
- deterministic read-file tool-call rendering;
- denied-outcome blocking for partial read-evidence recovery;
- small local helpers needed only by that handoff cluster.

`AssistantTurnExecutor` may keep package-private compatibility wrappers if
existing tests or call sites need them.

## T436 Guardrails

T436 must not change:

- `EvidenceGate` obligation selection;
- protected-read explicit-intent handling;
- unsupported capability handling;
- `talos.read_file` JSON shape;
- `ToolCallLoop` execution semantics;
- final answer wording;
- outcome dominance;
- mutation retry;
- read-only inspection retry;
- inspect-completeness retry;
- no-tool grounding retry;
- static-web answer overrides.

T436 should not move the new owner into `dev.talos.runtime.policy` or
`dev.talos.runtime.outcome`; executing the tool loop is not pure runtime policy
or pure outcome rendering.

## Proposed T436 Verification Shape

T436 should add focused coverage proving:

- non-protected read-target handoff executes a deterministic `talos.read_file`
  call and returns loop answer/summary evidence;
- protected targets without explicit read intent do not trigger handoff;
- unsupported-only expected targets use the same deterministic handoff path;
- partial read-evidence recovery does not retry after denied/protected evidence
  outcomes that intentionally block recovery;
- `AssistantTurnExecutor` compatibility wrappers preserve current behavior.

Then run:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Verification For This Ticket

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
