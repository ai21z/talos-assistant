# [T448-done-high] Extract Compact Read-Only Evidence Continuation

## Status

Done.

## Scope

T448 implements the T447 decision: extract only the compact read-only evidence
continuation from `ToolCallRepromptStage`.

This is an ownership refactor. It preserves runtime behavior and does not
change compact mutation continuation, exact-write context fallback,
missing-mutation retry, context-budget skipped retry wording, static repair
behavior, final answer wording, or outcome dominance.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `a95d2747`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` after extraction | 2621 lines |
| `CompactReadOnlyEvidenceContinuation.java` | 188 lines |
| Architecture baseline | 0 |

## Change

Added:

```text
dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuation
```

`CompactReadOnlyEvidenceContinuation` now owns:

- read-only evidence continuation eligibility;
- single-target readback selection for the required read-only target;
- compact read-only evidence answer message construction;
- compact answer backend call with no tools;
- rejection when the compact answer emits tool calls or empty text;
- terminal `LoopState` updates for the safe compact read-only answer;
- read-only evidence compact trace warnings.

`ToolCallRepromptStage` keeps lifecycle placement:

- detect context-budget overflow in tool-loop continuation;
- try compact mutation continuation first;
- ask `CompactReadOnlyEvidenceContinuation` whether a read-only evidence
  answer can be synthesized;
- preserve existing context-budget failure dominance when compact synthesis is
  not applicable or unsafe.

## Guardrails

Preserved:

- exact compact read-only evidence prompt wording;
- single-target readback selection;
- read-only review/proposal eligibility;
- `READ_ONLY_EVIDENCE_COMPACT_CONTINUATION` trace warning behavior;
- `READ_ONLY_EVIDENCE_COMPACT_REJECTED` rejection behavior;
- context-budget failure dominance when compact answer synthesis emits tool
  calls, returns empty text, or cannot run;
- no-tool compact answer contract;
- final answer behavior from the existing `ToolCallLoop` tests.

Not changed:

- compact mutation continuation;
- exact-write context fallback;
- missing-mutation retry;
- static repair behavior;
- action-obligation failure wording;
- `ResponseObligationVerifier` context-budget wording;
- final answer wording outside this compact read-only evidence continuation.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuationTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable CompactReadOnlyEvidenceContinuation
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuationTest" --no-daemon
```

Adjacent behavior verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.CompactReadOnlyEvidenceContinuationTest" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewUsesCompactEvidenceContinuationBeforeContextBudgetFailure" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceToolCallKeepsContextBudgetFailureDominant" --tests "dev.talos.runtime.ToolCallLoopTest.readOnlyReviewCompactEvidenceUsesRequestedTargetReadback" --tests "dev.talos.cli.modes.ExactWriteContextFallbackTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.exactLiteralWriteContextBudgetFallbackUsesCompactCurrentTurnPrompt" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationContextBudgetUsesCompactWriteRetryAfterReadOnlyProgress" --no-daemon
```

## Full Verification

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T448 is integrated, inspect the post-extraction `ToolCallRepromptStage`
shape before choosing T449.

Do not extract compact mutation continuation automatically. It remains a
stateful loop-control path and needs a separate boundary decision before code
movement.
