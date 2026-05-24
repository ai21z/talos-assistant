# [T422-done-high] AssistantTurnExecutor Answer-Shaping Boundary Decision

## Status

Done.

## Scope

T422 is a no-code inspection and decision ticket.

The goal is to inspect the post-T421 `AssistantTurnExecutor` answer-shaping
helpers and decide whether one coherent implementation extraction is justified.

This ticket intentionally does not change runtime behavior.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `e0edae1f`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 5653 lines |
| `ExecutionOutcome.java` | 680 lines |
| Architecture baseline | 0 |

## Source Inventory

`ExecutionOutcome` still delegates several final-answer correction steps back
to `AssistantTurnExecutor`:

- unsupported document claim correction;
- static-web import grounding;
- read-only web diagnostics;
- selector-search grounding;
- selector-mismatch grounding;
- read-only denied mutation summaries;
- denied mutation summaries;
- denied protected-read summaries;
- invalid mutation summaries;
- partial mutation summaries;
- false mutation-claim annotation;
- inspect-under-completion annotation.

That list is not one owner. Treating it as one owner would recreate a vague
answer-shaping warehouse under a new class name.

The inspected helper clusters split into these ownership candidates:

| Cluster | Current source | Ownership judgment |
|---|---|---|
| Mutation-failure answer summaries | `annotateIfFalseMutationClaim`, `summarizePartialMutationOutcomesIfNeeded`, `summarizeDeniedMutationOutcomesIfNeeded`, `summarizeReadOnlyDeniedMutationOutcomesIfNeeded`, `summarizeInvalidMutationOutcomesIfNeeded` | Coherent implementation candidate. |
| Protected-read denial summary | `summarizeDeniedProtectedReadOutcomesIfNeeded` | Keep separate from mutation failure; it belongs near protected-read answer safety. |
| Static-web grounding | `overrideSelectorMismatchAnalysisIfNeeded`, `overrideStaticSelectorSearchAnswerIfNeeded`, `overrideReadOnlyWebDiagnosticsIfNeeded`, `overrideStaticWebImportAnswerIfNeeded` | Do not move now; this crosses static verifier rendering, diagnostic intent, evidence obligations, and source surface checks. |
| Unsupported-document claim correction | `overrideUnsupportedDocumentClaimsIfNeeded` and its unsupported-path/content-claim helpers | Do not mix with mutation failure; this is beta capability truthfulness for document extraction limits. |
| No-tool/local-access truthfulness | `correctNegativeLocalAccessClaimIfNeeded`, `enforceStreamingNoToolTruthfulness`, `groundingRetryIfNeeded` | Do not move now; this includes streaming behavior and the non-streaming LLM retry path. |
| Inspect-under-completion annotation | `annotateIfInspectUnderCompletion` | Leave in place until broader no-tool/read-only truthfulness ownership is decided. |

## Existing Ownership Evidence

The runtime outcome package already owns adjacent mutation and truthfulness
concepts:

- `MutationOutcome`
- `MutationFailureRecovery`
- `TaskOutcomeWarningBuilder`
- `CommandOutcomeRenderer`
- `StaticVerificationAnswerRenderer`
- `ProtectedReadAnswerGuard`
- `EvidenceContainmentAnswerGuard`
- `ReadOnlyToolLimitOutcome`
- `UnsupportedDocumentCapabilityOutcome`

The mutation-failure answer-summary cluster is the only inspected answer-shaping
cluster that cleanly fits that package today. It consumes mutation tool outcomes,
task mutation intent, denial/failure classifications, and exact final-answer
wording. It does not need static-web analysis, document capability inspection,
protected-read content handling, or a model retry.

## Test Evidence

Existing tests already pin the mutation-failure wording and behavior through:

- `AssistantTurnExecutorTest` false mutation-claim tests;
- `AssistantTurnExecutorTest` partial mutation summary test;
- `AssistantTurnExecutorTest` denied mutation summary tests;
- `ExecutionOutcomeTest` denied mutation classification and final-answer tests;
- `ExecutionOutcomeTest` read-only denied mutation classification and final-answer
  tests;
- `ExecutionOutcomeTest` invalid mutation classification and final-answer tests;
- `ExecutionOutcomeTest` partial mutation classification and final-answer tests.

That means the next implementation ticket can be test-first without inventing a
large new behavior matrix.

## Rejected Options

### Move all answer shaping to one renderer

Rejected.

This would hide several separate policies behind one broad name. It would reduce
line count but make ownership less accurate.

### Extract static-web diagnostics next

Rejected for now.

Static-web answer grounding is meaningful, but it mixes static verifier
rendering, web diagnostic intent, linked-source evidence checks, selector search,
and selector mismatch analysis. That should get its own inspection ticket if we
return to it.

### Extract unsupported-document claim correction next

Rejected for now.

T420 extracted unsupported document capability detection, but the remaining
answer correction code rewrites content claims, family terms, search limitations,
and successful-read exceptions. It is important, but it is not the same owner as
mutation-failure rendering.

### Extract no-tool grounding retry next

Rejected for now.

`groundingRetryIfNeeded` can call the LLM again, while streaming no-tool
truthfulness is visible-output containment. That is behaviorally riskier than a
pure renderer extraction and should not be bundled with mutation summaries.

## Decision

The next implementation ticket should be:

```text
[T423] Extract mutation failure answer renderer
```

Target owner:

```text
dev.talos.runtime.outcome.MutationFailureAnswerRenderer
```

The target class should own only final-answer rendering for mutation failure
cases:

- false mutation-claim annotation;
- partial mutation outcome summary;
- denied mutation summary;
- read-only denied mutation summary;
- invalid mutation summary;
- local helper logic needed only by those renderers, such as failure-message
  trimming and read-only denied answer cleanup.

It should preserve exact wording, order, truncation, and pass/fail behavior.

It should not own:

- protected-read denied summaries;
- static-web grounding;
- unsupported document claim correction;
- no-tool local-access correction;
- streaming no-tool truthfulness;
- grounding retry;
- inspect-under-completion annotation;
- dominance policy;
- task outcome warning construction.

## T423 Implementation Shape

1. Create branch `T423` from fresh `origin/v0.9.0-beta-dev`.
2. Add focused RED ownership tests for `MutationFailureAnswerRenderer`.
3. Move only the mutation-failure renderer helpers out of
   `AssistantTurnExecutor`.
4. Leave compatibility delegating methods in `AssistantTurnExecutor` only if
   needed to avoid a broad test migration in the same ticket.
5. Update `ExecutionOutcome` to call the runtime outcome owner directly only if
   the resulting diff stays small and readable.
6. Preserve exact final-answer text and warning behavior.
7. Run focused renderer, `AssistantTurnExecutorTest`, and `ExecutionOutcomeTest`
   coverage before the full gate.

## Stop Conditions For T423

Stop and re-plan if the extraction requires any of these:

- changing answer wording;
- changing warning types or warning order;
- moving protected-read behavior;
- moving static-web behavior;
- moving unsupported-document behavior;
- changing no-tool retry or streaming behavior;
- expanding `MutationFailureAnswerRenderer` into a generic answer-shaping
  facade.

## Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
