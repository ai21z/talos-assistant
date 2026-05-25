# [T437-done-high] Read-Only Inspection Retry Boundary Decision

## Status

Done.

## Scope

T437 reinspects the post-T436 retry and handoff shape before choosing the next
implementation ticket.

This is a no-code decision ticket. It does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `a80ac968`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4550 lines |
| `ReadEvidenceHandoff.java` | 240 lines |
| Architecture baseline | 0 |

## Current Shape

T434 and T436 have removed the two clean retry/handoff units from
`AssistantTurnExecutor`:

- `PostToolSynthesisRetry` owns post-tool deflection synthesis retry.
- `ReadEvidenceHandoff` owns deterministic read-evidence handoff and partial
  read-evidence recovery.

The remaining retry methods are:

| Area | Source | Current risk |
|---|---|---|
| Read-only inspection retry | `readOnlyInspectionRetryIfNeeded(...)` | Moderate. It calls the model and may run the tool loop, but its owner is narrow: make one corrective no-tool read-only inspection attempt. |
| Missing-mutation retry | `mutationRequestRetryIfNeeded(...)` | High. It owns mutation obligations, tool-surface narrowing, static-repair failure modes, context-budget failure wording, and mutation evidence merging. |
| Inspect-completeness retry | `inspectCompletenessRetryIfNeeded(...)` | Moderate/high. It depends on primary-file and linked-script evidence, then merges retry loop evidence back into the original loop. |
| No-tool grounding retry | `groundingRetryIfNeeded(...)` | High for ownership movement. It mutates messages and calls the model on only the non-streaming no-tool branch. |

## Findings

### Missing-mutation retry still should not move next

The method remains execution-control heavy. It handles workspace-operation
retry tools, write/edit retry tools, static repair wrong-tool cases, invalid
mutation arguments, denied mutation, context-budget failures, compact retry
messages, and mutation retry evidence merging.

Extracting it next would be a risky behavior-preserving refactor with too many
policy seams.

### Inspect-completeness retry should wait

`inspectCompletenessRetryIfNeeded(...)` is coherent, but it is not the first
implementation slice after T436.

It depends on:

- `missingInspectReads(...)`;
- obvious primary file heuristics;
- linked-script read-target analysis;
- protected-path filtering;
- loop-result evidence merging.

That makes it better as a later ticket after the simpler no-tool read-only
retry is separated.

### No-tool grounding retry should remain in `AssistantTurnExecutor`

This has already been rejected as an outcome guard in earlier tickets. It is
still an LLM retry side effect scoped to non-streaming no-tool execution.

Moving it now would not improve ownership.

### Read-only inspection retry is now the next coherent implementation unit

After T436, direct read-evidence handoff is no longer mixed into the no-tool
branch. The remaining `readOnlyInspectionRetryIfNeeded(...)` path has one
clear job:

```text
If a read-only task required workspace evidence but the first answer used no
tools, make one corrective inspection attempt and, if the model emits tools,
run the tool loop.
```

That is a real owner:

```text
dev.talos.cli.modes.ReadOnlyInspectionRetry
```

It should stay in CLI turn-orchestration ownership because it calls the model,
mutates retry messages, and can run the configured `ToolCallLoop`.

## Decision

The next implementation ticket should be:

```text
[T438] Extract read-only inspection retry
```

Target owner:

```text
dev.talos.cli.modes.ReadOnlyInspectionRetry
```

T438 should move only:

- `ReadOnlyInspectionRetryResult`;
- `readOnlyInspectionRetryIfNeeded(...)`;
- `readOnlyInspectionRetryPrompt(...)`;
- the no-tool read-only retry message append order;
- the one-shot retry execution and optional tool-loop re-entry.

`AssistantTurnExecutor` should keep compatibility wrappers for existing tests.

The new owner should receive the model call through a small supplied chat
function from `AssistantTurnExecutor`, following the T434 pattern. Provider
controls and native tool surface behavior should still flow through the
existing `AssistantTurnExecutor.chatFull(...)` path.

## T438 Guardrails

T438 must preserve:

- exact retry prompt wording;
- directory-listing retry wording;
- explicit command verification retry wording;
- fallback primary-file wording;
- message append order;
- null/blank answer behavior;
- tool-call detection behavior;
- tool-loop execution behavior;
- returned answer/loop/summary semantics.

T438 must not change:

- direct read-evidence handoff;
- missing-mutation retry;
- inspect-completeness retry;
- no-tool grounding retry;
- `shapeAnswerWithoutTools(...)`;
- `shapeAnswerAfterToolLoop(...)`;
- streaming branch behavior;
- native tool-surface selection.

## Proposed T438 Verification Shape

T438 should add focused coverage proving:

- no owner exists before the implementation RED step;
- read-only evidence retry uses the same general prompt wording;
- directory-listing retry keeps list-only wording;
- explicit command verification retry keeps command-tool wording;
- a retry response containing tool calls re-enters the configured tool loop and
  returns loop answer/summary evidence.

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
