# [T439-done-high] Post Read-Only Retry Orchestration Boundary Decision

## Status

Done.

## Scope

T439 reinspects the retry/orchestration shape after T438 before selecting the
next implementation ticket.

This is a no-code decision ticket. It does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `30ae98a3`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4471 lines |
| Architecture baseline | 0 |

## Current Shape

The retry/handoff units already extracted from `AssistantTurnExecutor` are:

- `PostToolSynthesisRetry`;
- `ReadEvidenceHandoff`;
- `ReadOnlyInspectionRetry`.

The remaining retry/orchestration methods inspected in this ticket are:

| Area | Source lines | Ownership finding |
|---|---:|---|
| Missing-mutation retry | `mutationRequestRetryIfNeeded(...)` starts at lines 3045 and 3058 | Too broad for the next extraction. It mixes action obligations, mutation tool narrowing, trace recording, conditional review/fix behavior, static repair wrong-tool handling, invalid mutation failures, context-budget failure wording, approval denial handling, and mutation retry evidence merging. |
| Inspect-completeness retry | `inspectCompletenessRetryIfNeeded(...)` starts at lines 3816 and 3829 | Coherent, but not the next safest owner. It depends on static-web primary-file heuristics, linked-script read targets, protected-path filtering, and read-only evidence merge behavior. |
| No-tool grounding retry | `groundingRetryIfNeeded(...)` starts at lines 4420 and 4424 | Coherent and narrow. Detection constants, evidence-request matching, streaming predicates, and annotation text already live in `NoToolAnswerTruthfulnessGuard`; the remaining executor-owned part is the non-streaming retry side effect and message append. |

## Findings

### Missing-mutation retry should not move next

The method still owns too many policy and runtime outcomes at once:

- `ActionObligation` failure recording;
- mutation retry tool selection for write/edit and workspace-operation tools;
- compact retry tool spec construction;
- compact retry prompt construction;
- repair-follow-up reissue behavior;
- static repair wrong-tool detection;
- failed mutation target rendering;
- invalid mutation argument handling;
- context-budget retry-skip failure text;
- approval-denied mutation summary delegation;
- mutation retry loop evidence merging.

Extracting this next would be behavior-preserving only in name. The surface is
too large for a clean one-ticket move.

### Inspect-completeness retry should wait

`inspectCompletenessRetryIfNeeded(...)` has a real owner, but it is not isolated
enough for the immediate next implementation ticket.

It depends on:

- `StaticTaskVerifier.missingPrimaryReads(...)`;
- `EvidenceObligationVerifier.missingLinkedScriptReadTargets(...)`;
- `ProtectedPathPolicy.classify(...)`;
- read-path normalization;
- retry tool-loop re-entry;
- merged read-only loop evidence.

That is a legitimate future extraction, but it should follow a focused
inspection/guard ticket for static-web/evidence merge semantics or be taken as
its own implementation packet after the smaller grounding retry is separated.

### No-tool grounding retry is the next coherent implementation unit

The pure detection and annotation ownership is already outside
`AssistantTurnExecutor`:

- `NoToolAnswerTruthfulnessGuard.UNGROUNDED_MIN_CHARS`;
- `NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION`;
- `NoToolAnswerTruthfulnessGuard.looksLikeEvidenceRequest(...)`;
- `NoToolAnswerTruthfulnessGuard.shouldAppendStreamingGroundingAnnotation(...)`;
- `NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(...)`.

The remaining executor-owned behavior is a narrow non-streaming side effect:

```text
If the no-tool answer is long, evidence-looking, and not direct-answer-only,
append the original answer plus a corrective grounding prompt, call the model
once, and return either the different retry text or the annotated original.
```

That belongs in a small CLI turn-orchestration owner because it mutates the
turn messages and calls the model, but it does not need to live inside the main
executor class.

## Decision

The next implementation ticket should be:

```text
[T440] Extract no-tool grounding retry
```

Target owner:

```text
dev.talos.cli.modes.NoToolGroundingRetry
```

T440 should move only:

- the non-streaming `groundingRetryIfNeeded(...)` retry side effect;
- the corrective grounding retry prompt string;
- the supplied chat call seam;
- the retry/annotation fallback result logic.

`AssistantTurnExecutor` should keep compatibility wrappers for existing tests.

## T440 Guardrails

T440 must preserve:

- exact corrective prompt wording;
- minimum-length behavior;
- direct-answer-only/small-talk bypass behavior;
- latest-user-request selection behavior;
- evidence-request matching through `NoToolAnswerTruthfulnessGuard`;
- message append order;
- retry text replacement behavior;
- fallback annotation behavior;
- exception logging behavior.

T440 must not change:

- streaming grounding annotation;
- no-tool mutation replacement;
- negative local access correction;
- read-only inspection retry;
- inspect-completeness retry;
- missing-mutation retry;
- outcome warning construction;
- whether retry tool calls are executed.

The last point is deliberate: the current non-streaming grounding retry calls
the model but does not re-enter the tool loop. Whether that is the right product
behavior is a separate design decision. T440 is an ownership extraction, not a
semantic correction.

## Proposed T440 Verification Shape

T440 should add focused coverage proving:

- the new owner exists and owns the message append/retry behavior;
- long evidence-looking no-tool answers still append assistant plus corrective
  user messages in the same order;
- retry text replacement behavior is unchanged;
- blank/identical/exception retry paths still return the annotated original;
- direct-answer-only and short-answer cases do not fire.

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
