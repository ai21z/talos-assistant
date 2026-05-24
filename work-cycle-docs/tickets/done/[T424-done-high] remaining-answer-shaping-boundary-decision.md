# [T424-done-high] Remaining Answer-Shaping Boundary Decision

## Status

Done.

## Scope

T424 is a no-code inspection and decision ticket after T423.

The goal is to inspect the remaining `AssistantTurnExecutor` answer-shaping
responsibilities and decide the next correct implementation slice.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `ad583e5e`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `AssistantTurnExecutor.java` | 5317 lines |
| `ExecutionOutcome.java` | 681 lines |
| Architecture baseline | 0 |

## Post-T423 Shape

T423 moved mutation-failure final-answer rendering into
`MutationFailureAnswerRenderer`.

`ExecutionOutcome` still calls these `AssistantTurnExecutor` answer-shaping
helpers:

- `overrideUnsupportedDocumentClaimsIfNeeded`;
- `overrideStaticWebImportAnswerIfNeeded`;
- `overrideReadOnlyWebDiagnosticsIfNeeded`;
- `overrideStaticSelectorSearchAnswerIfNeeded`;
- `overrideSelectorMismatchAnalysisIfNeeded`;
- `summarizeDeniedProtectedReadOutcomesIfNeeded`;
- `annotateIfInspectUnderCompletion`;
- `correctNegativeLocalAccessClaimIfNeeded`;
- `enforceStreamingNoToolTruthfulness`;
- `groundingRetryIfNeeded`.

Those helpers are still not one owner.

## Ownership Findings

### Protected-read denial summary

`summarizeDeniedProtectedReadOutcomesIfNeeded` is a small, coherent ownership
slice.

It renders a deterministic answer when `talos.read_file` was denied by approval
for protected content. It does not need static-web analysis, document family
classification, streaming behavior, or an LLM retry.

Existing adjacent owner:

```text
dev.talos.runtime.outcome.ProtectedReadAnswerGuard
```

That class already owns protected-read answer safety:

- approved protected-read postcondition enforcement;
- generic refusal replacement after an approved protected read;
- protected-history suppression when the current turn lacks an approved read;
- protected path/alias detection for answer containment.

The denied protected-read summary should move there next.

### Unsupported-document answer correction

`overrideUnsupportedDocumentClaimsIfNeeded` is coherent but larger.

It handles unsupported read paths, unsupported grep/search limitations,
unsupported document family terms, successful-read exceptions, and content-claim
sentence removal. It should become a separate document-capability truthfulness
owner later, likely near `UnsupportedDocumentCapabilityOutcome`, but it is not
the next smallest correct implementation slice.

### Static-web answer grounding

The static-web helpers are related, but they are still a mixed cluster:

- static import inspection;
- read-only web diagnostics;
- selector search grounding;
- selector mismatch grounding;
- linked-script evidence checks;
- static verifier rendering.

Moving them as one class now would risk creating another broad answer-grounding
warehouse. If this lane continues after protected-read denial, static-web
grounding needs its own inspection ticket or a deliberately named owner.

### No-tool and streaming truthfulness

`correctNegativeLocalAccessClaimIfNeeded`,
`enforceStreamingNoToolTruthfulness`, and `groundingRetryIfNeeded` are important
but not a cheap extraction.

They mix:

- no-tool final-answer replacement;
- streaming-visible mutation containment;
- streaming grounding annotation;
- non-streaming LLM retry;
- direct-answer-only exemptions;
- local workspace capability correction.

This is behaviorally riskier than a pure deterministic renderer and should not
be bundled with protected-read or static-web movement.

### Inspect-under-completion

`annotateIfInspectUnderCompletion` is small, but it depends on the broader
read-only/no-tool truthfulness model. It should stay put until the no-tool and
inspection-answer ownership lane is decided.

## Decision

The next implementation ticket should be:

```text
[T425] Move protected read denial summary to protected read answer guard
```

Target owner:

```text
dev.talos.runtime.outcome.ProtectedReadAnswerGuard
```

T425 should move only denied protected-read answer rendering out of
`AssistantTurnExecutor`.

Expected implementation shape:

1. Add RED tests to `ProtectedReadAnswerGuardTest` for denied protected-read
   summary rendering and display-path canonicalization.
2. Add `ProtectedReadAnswerGuard.summarizeDeniedProtectedReadOutcomesIfNeeded`.
3. Update `ExecutionOutcome` to call `ProtectedReadAnswerGuard` directly.
4. Keep an `AssistantTurnExecutor` compatibility wrapper only if needed for
   existing package-private tests.
5. Preserve exact final answer wording and classification behavior.

## Explicit Non-Scope For T425

Do not move:

- approved protected-read postcondition behavior;
- prior protected-history suppression behavior;
- protected path policy classification;
- unsupported document correction;
- static-web grounding;
- no-tool grounding retry;
- streaming no-tool truthfulness;
- inspect-under-completion annotation.

## Stop Conditions For T425

Stop and re-plan if the extraction requires:

- changing protected-read denial wording;
- changing `TruthWarningType.DENIED_PROTECTED_READ`;
- changing `ExecutionOutcome` completion status decisions;
- changing approved protected-read postcondition behavior;
- adding a broad generic answer renderer.

## Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
