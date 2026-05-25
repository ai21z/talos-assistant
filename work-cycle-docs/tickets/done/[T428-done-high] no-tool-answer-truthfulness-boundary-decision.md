# [T428-done-high] No-Tool Answer Truthfulness Boundary Decision

## Status

Done.

## Scope

T428 reinspects the post-T427 answer-shaping surface in
`AssistantTurnExecutor` and `ExecutionOutcome`.

This is a no-code decision ticket. T427 moved unsupported-document answer
correction into runtime outcome ownership; T428 decides the next coherent
answer-truthfulness owner.

## Source Evidence

`ExecutionOutcome` still reaches back into `AssistantTurnExecutor` for:

- static-web answer overrides;
- inspect-under-completion annotation;
- malformed no-tool protocol replacement;
- negative local workspace access correction;
- streaming no-tool mutation/truthfulness correction;
- non-streaming no-tool grounding retry;
- compatibility constants used by existing tests and task-outcome warning
  markers.

The no-tool truthfulness cluster in `AssistantTurnExecutor` contains two
different responsibilities:

1. Pure answer truthfulness predicates and replacements:
   - malformed protocol replacement text;
   - local workspace access capability correction;
   - streaming no-tool mutation replacement/annotation;
   - ungrounded no-tool annotation;
   - marker and predicate logic over answer text, latest user request, and
     `CurrentTurnPlan`.
2. Non-streaming grounding retry orchestration:
   - mutates the message list;
   - calls the LLM through CLI `Context`;
   - uses `chatFull(...)`;
   - logs retry behavior.

Those two responsibilities should not be moved together.

## Decision

Do not move the full no-tool branch as one extraction.

The next implementation slice should be:

```text
[T429] Extract no-tool answer truthfulness guard
```

T429 should extract only the pure answer-truthfulness predicates and rendering
into runtime outcome ownership, likely:

```text
dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard
```

T429 should leave `groundingRetryIfNeeded(...)` in `AssistantTurnExecutor`
because it is LLM retry orchestration, not pure outcome rendering.

## T429 Intended Ownership

The new guard may own:

- `UNGROUNDED_ANNOTATION`;
- `STREAMING_NO_TOOL_MUTATION_ANNOTATION`;
- `STREAMING_NO_TOOL_MUTATION_REPLACEMENT`;
- `MALFORMED_TOOL_PROTOCOL_REPLACEMENT`;
- `LOCAL_ACCESS_CAPABILITY_CORRECTION`;
- negative local access claim detection;
- local-workspace turn detection;
- streaming no-tool mutation narrative detection;
- streaming no-tool truthfulness enforcement;
- streaming no-tool grounding annotation predicate.

`AssistantTurnExecutor` may keep compatibility constants/wrappers if existing
tests still need them.

`ExecutionOutcome.fromNoTool(...)` should call the guard directly for the pure
branches. It should continue to call `AssistantTurnExecutor.groundingRetryIfNeeded(...)`
for the non-streaming retry branch.

## Rejected Next Slices

Static-web answer overrides are rejected for T429. They remain coupled to
static-web diagnostic semantics, selector mismatch reasoning, linked-script
evidence, and earlier static-web movement rejections.

Inspect-under-completion is rejected for T429. It is coupled to inspect-first
intent, read counts, missing-inspection policy, and retry-vs-annotation
decisions.

Non-streaming no-tool grounding retry is rejected for T429. It is not pure
rendering because it calls the LLM and mutates retry messages.

Direct deterministic answers and session-evidence follow-up remain outside the
T429 scope.

## T429 Guardrails

T429 should:

- start from fresh `origin/v0.9.0-beta-dev`;
- add a focused RED ownership test for the new no-tool answer guard;
- preserve exact replacement and annotation wording;
- preserve streaming-vs-non-streaming behavior;
- keep the LLM grounding retry in `AssistantTurnExecutor`;
- avoid static-web, inspect-under-completion, session-evidence, and direct
  deterministic answer movement;
- run focused no-tool/ExecutionOutcome tests;
- run `validateArchitectureBoundaries`;
- run full `check`.

## Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T428 integrates cleanly, start T429 from fresh beta and extract only the
pure no-tool answer truthfulness guard.
