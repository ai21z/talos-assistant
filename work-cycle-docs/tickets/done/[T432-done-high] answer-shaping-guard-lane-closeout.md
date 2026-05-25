# [T432-done-high] Answer-Shaping Guard Lane Closeout

## Status

Done.

## Scope

T432 reinspects the post-T431 answer-shaping surface in
`AssistantTurnExecutor` and `ExecutionOutcome`.

This is a no-code closeout and decision ticket. It does not change runtime
behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `6d84ab8b`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 4815 lines |
| `ExecutionOutcome.java` | 685 lines |
| Architecture baseline | 0 |

## Post-T431 Shape

The deterministic answer-shaping guard extractions now have clear runtime
owners:

- mutation-failure answer rendering:
  `dev.talos.runtime.outcome.MutationFailureAnswerRenderer`;
- protected-read answer safety:
  `dev.talos.runtime.outcome.ProtectedReadAnswerGuard`;
- unsupported-document answer correction:
  `dev.talos.runtime.outcome.UnsupportedDocumentAnswerGuard`;
- no-tool answer truthfulness:
  `dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard`;
- inspect under-completion annotation:
  `dev.talos.runtime.outcome.InspectUnderCompletionAnswerGuard`;
- evidence containment:
  `dev.talos.runtime.outcome.EvidenceContainmentAnswerGuard`;
- command outcome rendering:
  `dev.talos.runtime.outcome.CommandOutcomeRenderer`;
- static verification answer rendering:
  `dev.talos.runtime.outcome.StaticVerificationAnswerRenderer`.

`ExecutionOutcome` still reaches back into `AssistantTurnExecutor` for:

- `overrideStaticWebImportAnswerIfNeeded(...)`;
- `overrideReadOnlyWebDiagnosticsIfNeeded(...)`;
- `overrideStaticSelectorSearchAnswerIfNeeded(...)`;
- `overrideSelectorMismatchAnalysisIfNeeded(...)`;
- `groundingRetryIfNeeded(...)`;
- compatibility marker text for read-only denied mutation.

Those remaining calls are not one coherent "answer guard" owner.

## Ownership Findings

### Static-web deterministic answer overrides

The static-web override cluster is related, but it is still mixed:

- static import inspection;
- read-only web diagnostics;
- static selector search;
- selector mismatch analysis;
- linked-script evidence checks;
- `StaticTaskVerifier` rendering;
- static-web intent classification.

Earlier static-web lane work already closed the static-web verifier extraction
lane and rejected casual static-web diagnostic movement. Moving this cluster now
would not be a small answer-guard cleanup; it would reopen static-web ownership.

### Non-streaming no-tool grounding retry

`groundingRetryIfNeeded(...)` is not a pure answer guard.

It:

- mutates the message list;
- calls the LLM through `chatFull(...)`;
- depends on CLI `Context`;
- uses `CurrentTurnPlan` and direct-answer-only exemptions;
- may return retry text or annotate the original answer after retry failure.

That is retry orchestration, not final-answer rendering. Moving it into
`dev.talos.runtime.outcome` would make the runtime outcome package own an LLM
retry side effect, which is the wrong boundary.

### Compatibility constants

The remaining compatibility marker references from tests and containment marker
construction are not a standalone ticket. They are low-value surface polish
unless tied to a real ownership change.

## Decision

Close the answer-shaping guard extraction lane for now.

The remaining `AssistantTurnExecutor` answer-shaping dependencies should not be
mechanically extracted just to reduce call count. The deterministic guard work
has reached a steady state. Further movement requires a new lane and a fresh
boundary decision.

## Rejected Next Slices

### Extract static-web answer overrides now

Rejected.

This would reopen static-web ownership after that lane was deliberately closed.
It crosses verifier rendering, intent classification, linked-source evidence,
and selector semantics.

### Extract no-tool grounding retry as an outcome guard

Rejected.

It calls the LLM and mutates retry messages. That is orchestration, not a pure
runtime outcome guard.

### Remove compatibility constants only

Rejected.

That would be surface cleanup with little architectural value and unnecessary
test churn.

## Next Correct Move

Start a new inspection/decision ticket before implementation:

```text
[T433] AssistantTurnExecutor Retry Orchestration Boundary Decision
```

T433 should inspect retry orchestration as its own lane, including:

- non-streaming no-tool grounding retry;
- inspect-completeness retry;
- mutation retry/evidence retry paths if they still sit in
  `AssistantTurnExecutor`;
- what must remain in the CLI turn executor because it uses `Context`,
  `chatFull(...)`, streaming/non-streaming output timing, or message-list
  mutation.

T433 should not implement an extraction unless that inspection proves a
coherent owner and a behavior-preserving slice.

## Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
