# [T421-done-high] Execution Outcome Lane Closeout

## Status

Done.

## Scope

T421 is a no-code inspection and closeout ticket.

The goal is to inspect the post-T420 `ExecutionOutcome` shape and decide
whether another immediate implementation extraction is justified.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `d71102b4`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ExecutionOutcome.java` | 680 lines |
| `AssistantTurnExecutor.java` | 5653 lines |
| Architecture baseline | 0 |

Recent extracted owners:

- `CommandOutcomeRenderer`
- `StaticVerificationAnswerRenderer`
- `TaskOutcomeWarningBuilder`
- `ProtectedReadAnswerGuard`
- `EvidenceContainmentAnswerGuard`
- `TaskOutcomeTraceRecorder`
- `EmbeddedStaticVerificationResultParser`
- `EvidenceObligationAssessment`
- `ActionObligationFailureAssessment`
- `ReadOnlyToolLimitOutcome`
- `UnsupportedDocumentCapabilityOutcome`
- `MutationOutcome`

## Current Source Shape

`ExecutionOutcome` is no longer the primary policy warehouse it was at the start
of this lane. It is now mostly an orchestration facade for end-of-turn outcome
classification.

The remaining direct responsibilities are:

1. choosing the compatibility `CurrentTurnPlan` fallback for legacy callers;
2. sequencing legacy `AssistantTurnExecutor` answer-shaping helpers;
3. invoking command outcome rendering;
4. invoking evidence containment and protected-read guards;
5. deciding whether post-apply static verification should run;
6. mapping `TaskVerificationStatus` to the local `ExecutionOutcome` enum;
7. assembling `OutcomeDominancePolicy.Facts`;
8. assembling `TaskOutcome`;
9. recording the final task outcome trace through `TaskOutcomeTraceRecorder`;
10. shaping the no-tool path.

## Decision

Do not extract another piece from `ExecutionOutcome` immediately.

The remaining code is not uniformly cheap. The obvious next-looking block is
the legacy `AssistantTurnExecutor` answer-shaping sequence, but that sequence is
not one owner. It mixes:

- unsupported document claim correction;
- static-web import grounding;
- read-only web diagnostics;
- selector-search grounding;
- selector-mismatch analysis;
- read-only denied mutation summaries;
- denied mutation summaries;
- denied protected-read summaries;
- invalid mutation summaries;
- partial mutation summaries;
- false mutation-claim annotation;
- inspect-under-completion annotation.

Moving that entire block would create a new vague answer-shaping warehouse. That
would reduce line count while making ownership less true.

Post-apply verification dispatch is also not a clean extraction yet. It depends
on `ExecutionOutcome.CompletionStatus`, `ExecutionOutcome.VerificationStatus`,
embedded verification fallback, final-answer rendering, and dominance timing.
Moving it now would either force runtime code to depend on CLI-local enums or
force a larger status-model decision. That is not a safe small ticket.

## What This Lane Improved

The execution-outcome lane now has explicit owners for:

- command conclusion and command verification wording;
- static-verification answer rendering;
- task outcome warnings;
- protected-read answer safety;
- evidence-containment answer safety;
- structured task outcome trace recording;
- embedded static-verification result parsing;
- evidence-obligation assessment;
- action-obligation failure assessment;
- read-only tool-limit truthfulness;
- unsupported document capability outcome detection;
- mutation outcome facts.

This is useful architecture. It did not merely split files. It moved repeated
runtime truthfulness facts and final-answer safety rules into named owners that
can be tested directly.

## Remaining Risk

`ExecutionOutcome` is still coupled to `AssistantTurnExecutor` because the
answer-shaping helpers live there. That is now the larger ownership problem, not
another small `ExecutionOutcome` helper.

`AssistantTurnExecutor` is still a major policy and orchestration concentration
point. It currently owns or exposes helper methods for final-answer correction,
static-web grounding, mutation-denial summarization, no-tool truthfulness, and
grounding retry behavior.

## Next Correct Move

The next ticket should be a decision/inventory ticket, not an implementation
ticket:

```text
[T422] AssistantTurnExecutor Answer-Shaping Boundary Decision
```

T422 should inspect the answer-shaping helpers from source evidence and decide
whether there is one coherent implementation owner. Candidate tracks to inspect:

- static-web answer grounding;
- mutation failure answer summaries;
- no-tool truthfulness shaping;
- unsupported capability answer correction;
- selector-search and selector-mismatch grounding.

If no coherent owner is proven, stop and plan the broader Talos testing lane
instead of forcing another refactor.

## Verification

Required gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed.
