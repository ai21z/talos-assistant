# [T430-done-high] Inspect Under-Completion Boundary Decision

## Status

Done.

## Scope

T430 reinspects the post-T429 answer-shaping surface in
`AssistantTurnExecutor` and `ExecutionOutcome`.

This is a no-code decision ticket. T429 moved pure no-tool answer truthfulness
into runtime outcome ownership; T430 decides the next coherent answer-shaping
owner.

## Source Evidence

After T429, `ExecutionOutcome` still reaches back into
`AssistantTurnExecutor` for:

- static-web answer overrides;
- inspect-under-completion annotation;
- non-streaming no-tool grounding retry;
- one compatibility marker for read-only denied mutation.

The remaining inspect-related code in `AssistantTurnExecutor` is split into two
different responsibilities:

1. Inspect-completeness retry orchestration:
   - computes missing primary reads;
   - builds retry prompts;
   - mutates retry messages;
   - calls the tool loop/LLM path;
   - merges retry evidence.
2. Inspect-under-completion final-answer annotation:
   - checks answer length;
   - checks current tool-loop shape;
   - checks inspect-first wording;
   - prepends a deterministic warning string.

Those should not be moved together.

## Decision

The next implementation slice should be:

```text
[T431] Extract inspect under-completion answer guard
```

T431 should move only the pure final-answer annotation predicate and rendering
into runtime outcome ownership, likely:

```text
dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard
```

T431 should leave inspect-completeness retry orchestration in
`AssistantTurnExecutor`.

## T431 Intended Ownership

The new guard may own:

- `INSPECT_MIN_CHARS`;
- `UNDER_INSPECTION_ANNOTATION`;
- inspect-first request marker detection;
- read-only tool-count detection;
- `annotateIfInspectUnderCompletion(...)`.

`ExecutionOutcome.fromToolLoop(...)` should call the guard directly.

`AssistantTurnExecutor` may keep compatibility constants/wrappers if existing
tests still need them.

## Rejected Next Slices

Static-web answer overrides are rejected for T431. They remain coupled to
static-web diagnostic rendering, selector mismatch analysis, import checks,
linked-script evidence, and earlier static-web movement rejections.

Inspect-completeness retry is rejected for T431. It is orchestration, not pure
answer rendering, because it builds retry prompts, calls runtime loops, and
merges evidence.

Non-streaming no-tool grounding retry is rejected for T431. T428 already
recorded that it is LLM retry orchestration and should not move with pure
answer guards.

## T431 Guardrails

T431 should:

- start from fresh `origin/v0.9.0-beta-dev`;
- add a focused RED ownership test for the new inspect under-completion guard;
- preserve exact annotation wording;
- preserve all inspect-completeness retry behavior;
- preserve static-web answer overrides;
- preserve no-tool grounding retry behavior;
- run focused guard/ExecutionOutcome/AssistantTurnExecutor tests;
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

After T430 integrates cleanly, start T431 from fresh beta and extract only the
inspect under-completion answer guard.
