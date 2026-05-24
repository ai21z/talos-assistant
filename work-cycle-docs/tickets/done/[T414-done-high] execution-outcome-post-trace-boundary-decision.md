# [T414-done-high] Execution Outcome Post-Trace Boundary Decision

## Status

Done.

## Scope

T414 is a no-code inspection and decision ticket.

The goal is to inspect the post-T413 `ExecutionOutcome` shape and choose the
next coherent ownership move. T414 intentionally does not extract code.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `d608bfa1`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ExecutionOutcome.java` | 797 lines |
| Architecture baseline | 0 |

Recent extracted owners:

- `CommandOutcomeRenderer`
- `StaticVerificationAnswerRenderer`
- `TaskOutcomeWarningBuilder`
- `ProtectedReadAnswerGuard`
- `EvidenceContainmentAnswerGuard`
- `TaskOutcomeTraceRecorder`
- `MutationOutcome`

## Current Source Shape

`ExecutionOutcome` remains an end-of-turn orchestration facade. It is cleaner
than the original monolith, but it still owns several distinct boundary
clusters:

1. `fromToolLoop(...)` and `fromNoTool(...)` orchestration;
2. compatibility answer shaping through legacy `AssistantTurnExecutor` helper
   calls;
3. command conclusion branching through `CommandOutcomeRenderer`;
4. evidence-obligation adaptation around `EvidenceObligationVerifier`;
5. protected-read and evidence-containment answer guards;
6. read-only tool-loop-limit replacement;
7. post-apply static-verification invocation;
8. embedded static-verification fallback parsing;
9. outcome dominance calls through `OutcomeDominancePolicy`;
10. `TaskOutcome` assembly;
11. final structured trace recording through `TaskOutcomeTraceRecorder`.

T413 removed the final structured trace write logic from `ExecutionOutcome`.
The remaining direct trace calls are specific protocol-sanitized events, not
the structured outcome summary.

## Source Evidence

The remaining embedded static-verification parser is local to
`ExecutionOutcome`:

```text
embeddedStaticVerificationFailure(String answer)
embeddedStaticVerificationProblems(String answer)
```

It reads the exact answer fragment produced by static-verification failure
rendering:

```text
[Task incomplete: Static verification failed - ...]

Unresolved static verification problems:
- ...
```

and turns that rendered text back into a `TaskVerificationResult.failed(...)`
so that blocked action-obligation turns still record failed verification in
`ExecutionOutcome.verificationStatus()`, `TaskOutcome.verificationResult()`,
and local trace outcome evidence.

The current regression anchor is:

```text
ExecutionOutcomeTest.embeddedStaticVerificationFailureInBlockedToolLoopIsRecordedInOutcomeAndTrace
```

That test proves the fallback is behaviorally important: a blocked tool loop
whose answer already contains a static-verification failure must keep the exact
answer intact while still reporting verification `FAILED` in outcome and trace
state.

## Candidate Boundaries Considered

### Candidate A: Evidence-obligation adapter

`ExecutionOutcome` still adapts `CurrentTurnPlan`, `ToolCallLoop.LoopResult`,
and expected/source targets into `EvidenceObligationVerifier`.

This is real ownership friction, but it is not the next move. The adapter mixes
legacy loop-result fallback, current-turn evidence policy, protected-read
approval state, static-web diagnosis evidence, unsupported document capability
checks, and tool alias normalization. Moving it correctly probably needs a
small `EvidenceAssessment` model, not only a method extraction.

Decision: inspect later; do not use T415 for this.

### Candidate B: Read-only tool-loop-limit answer replacement

The replacement string and predicate are small:

```text
READ_ONLY_TOOL_LIMIT_REPLACEMENT
readOnlyToolLimitWithoutRuntimeAnswer(...)
```

This is coherent, but too small to be the next main ownership move. It is also
partly a final-answer rendering concern and partly a loop-limit/evidence
truthfulness concern.

Decision: postpone until the surrounding evidence/truthfulness ownership is
clearer.

### Candidate C: Embedded static-verification fallback parser

This is the cleanest next implementation unit.

It has one job: parse a rendered static-verification failure answer fragment
into a `TaskVerificationResult` without changing the answer. The owner should
be verification-facing, because the result is verification state, not final
answer rendering or dominance.

Decision: implement next.

## Decision

The next implementation ticket should be:

```text
[T415] Extract embedded static verification result parser
```

Target class:

```text
dev.talos.runtime.verification.EmbeddedStaticVerificationResultParser
```

Target responsibility:

- detect the embedded static-verification failure marker;
- extract the rendered summary between the marker and the closing bracket or
  line end;
- extract rendered `Unresolved static verification problems:` bullet lines;
- return `TaskVerificationResult.failed(...)` when an embedded failure exists;
- return `TaskVerificationResult.notRun("Post-apply verification was not applicable.")`
  when no embedded failure exists.

`ExecutionOutcome` should still own:

- deciding when to inspect the current answer for an embedded failure;
- choosing between real `StaticTaskVerifier.verify(...)`, embedded fallback,
  and not-run verification;
- preserving the already-rendered answer when the embedded failure is part of a
  dominant action-obligation failure;
- outcome dominance and final answer shaping.

## Rejected Alternatives

### Move compatibility answer-shaping calls out of `ExecutionOutcome`

Rejected for T415.

The remaining `AssistantTurnExecutor` calls are not one boundary. They include
unsupported document truthfulness, static-web import grounding, read-only web
diagnostics, selector mismatch grounding, denied mutation summaries, protected
read denial summaries, invalid/partial mutation summaries, false mutation
claim annotations, and inspect-under-completion annotations. Moving that block
mechanically would hide several policy types behind one new broad class.

### Move `OutcomeDominancePolicy` now

Rejected.

`OutcomeDominancePolicy` still consumes `ExecutionOutcome.VerificationStatus`
and returns `ExecutionOutcome.CompletionStatus`. Moving it cleanly needs a
runtime-owned status model or a deliberate decision to keep these nested enums.
That is a larger design step than T415.

### Extract evidence-obligation adapter now

Rejected for immediate implementation.

This should be a future decision/implementation pair. The adapter touches too
many adjacent truthfulness and evidence concerns to move as a casual cleanup.

## T415 Test Shape

Recommended RED/GREEN tests:

- parser returns `NOT_RUN` with the exact existing not-run summary when no
  embedded marker exists;
- parser extracts summary and bullet problems from a full failed replacement;
- parser falls back to the summary as the only problem when no bullet problems
  are present;
- parser uses `"Static verification failed."` when the rendered summary is
  blank or malformed;
- `ExecutionOutcomeTest.embeddedStaticVerificationFailureInBlockedToolLoopIsRecordedInOutcomeAndTrace`
  still passes unchanged.

Recommended focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.EmbeddedStaticVerificationResultParserTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Required final gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Results:

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- `.\gradlew.bat check --no-daemon`: passed.

## Next

After T414 integrates cleanly, start T415 from fresh `origin/v0.9.0-beta-dev`
and extract only `EmbeddedStaticVerificationResultParser`. Do not move
evidence-obligation adaptation, read-only limit handling, dominance policy, or
compatibility answer-shaping in the same ticket.
