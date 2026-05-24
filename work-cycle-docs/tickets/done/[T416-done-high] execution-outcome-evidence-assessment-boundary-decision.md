# [T416-done-high] Execution Outcome Evidence Assessment Boundary Decision

## Status

Done.

## Scope

T416 is a no-code inspection and decision ticket.

The goal is to inspect the post-T415 `ExecutionOutcome` shape and choose the
next coherent ownership move. T416 intentionally does not extract code.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `a6bcdd7b`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ExecutionOutcome.java` | 756 lines |
| Architecture baseline | 0 |

Recent extracted owners:

- `CommandOutcomeRenderer`
- `StaticVerificationAnswerRenderer`
- `TaskOutcomeWarningBuilder`
- `ProtectedReadAnswerGuard`
- `EvidenceContainmentAnswerGuard`
- `TaskOutcomeTraceRecorder`
- `EmbeddedStaticVerificationResultParser`
- `MutationOutcome`

## Current Source Shape

`ExecutionOutcome` is now a narrower orchestrator, but it still directly owns
several helper clusters:

1. compatibility answer shaping through legacy `AssistantTurnExecutor` helper
   calls;
2. evidence-obligation adaptation around `EvidenceObligationVerifier`;
3. unsupported document capability outcome detection;
4. action-obligation failure fact derivation;
5. read-only tool-loop-limit truthfulness replacement;
6. post-apply verification dispatch through `StaticTaskVerifier`;
7. outcome dominance call assembly;
8. no-tool truthfulness/evidence shaping.

The next move should remove a real ownership concern without folding unrelated
truthfulness mechanisms into one broad class.

## Source Evidence

Evidence assessment is currently embedded in both `fromToolLoop(...)` and
`fromNoTool(...)`:

```text
EvidenceObligation evidenceObligation = evidenceObligation(safePlan);
EvidenceObligationVerifier.Result evidenceResult = verifyEvidence(...);
boolean missingEvidence =
    evidenceResult.status() == EvidenceObligationVerifier.Status.UNSATISFIED;
boolean protectedReadApprovalMissing =
    protectedReadApprovalMissing(evidenceObligation, evidenceResult);
```

The helper methods live at the bottom of `ExecutionOutcome`:

```text
evidenceObligation(CurrentTurnPlan)
verifyEvidence(CurrentTurnPlan, List<ToolOutcome>, Path)
protectedReadApprovalMissing(EvidenceObligation, EvidenceObligationVerifier.Result)
evidenceTargets(TaskContract)
evidenceOutcomes(ToolCallLoop.LoopResult)
```

Those methods do not decide final answer wording, command rendering,
post-apply verification, trace recording, or outcome dominance. They adapt the
current turn plan and loop evidence into a policy verdict.

That owner already exists conceptually in runtime policy:

```text
dev.talos.runtime.policy.EvidenceObligationPolicy
dev.talos.runtime.policy.EvidenceObligationVerifier
dev.talos.runtime.policy.EvidenceGate
```

So keeping the adapter inside `ExecutionOutcome` is now the clearest remaining
ownership leak.

## Candidate Boundaries Considered

### Candidate A: Read-only tool-loop-limit rendering

`READ_ONLY_TOOL_LIMIT_REPLACEMENT` and
`readOnlyToolLimitWithoutRuntimeAnswer(...)` are coherent and small.

Rejected for T417. It is a real cleanup, but it is narrower than the evidence
assessment leak and still sits in the middle of read-only evidence truthfulness
and answer rendering. It can be handled after evidence assessment is out.

### Candidate B: Compatibility answer-shaping block

The `AssistantTurnExecutor` calls are large and visible, but they are not one
owner. The block includes unsupported document claims, static-web import
grounding, read-only web diagnostics, selector grounding, denied mutation
summaries, protected-read denial summaries, invalid/partial mutation summaries,
false mutation claim annotations, and inspect-under-completion annotations.

Rejected for T417. Moving this block as one unit would create a vague
answer-shaping warehouse and lose the fine-grained ownership discipline used in
the previous tickets.

### Candidate C: Action-obligation failure fact derivation

`failurePolicyStoppedWithoutMutation(...)`,
`pendingActionObligationFailure(...)`, and `hasDeniedMutation(...)` are
coherent policy/fact helpers.

Postponed. This is a reasonable future ticket, but evidence assessment is used
by both tool-loop and no-tool paths and has a clearer existing package home.

### Candidate D: Evidence-obligation assessment

Selected.

The adapter has one job: convert current-turn plan and gathered evidence into
the evidence result fields that outcome shaping consumes. This belongs beside
`EvidenceObligationVerifier`, not inside `ExecutionOutcome`.

## Decision

The next implementation ticket should be:

```text
[T417] Extract evidence obligation assessment
```

Target class:

```text
dev.talos.runtime.policy.EvidenceObligationAssessment
```

Recommended public shape:

```text
public record EvidenceObligationAssessment(
    EvidenceObligation obligation,
    EvidenceObligationVerifier.Result result
) {
    public static EvidenceObligationAssessment assess(
        CurrentTurnPlan plan,
        ToolCallLoop.LoopResult loopResult,
        Path workspace
    )

    public boolean missingEvidence()
    public boolean protectedReadApprovalMissing()
}
```

The class should own:

- parsing the plan's `EvidenceObligation`;
- selecting source evidence targets over expected targets;
- adapting legacy `LoopResult.toolNames()` and `readPaths()` into synthetic
  evidence outcomes only when richer `toolOutcomes()` are absent;
- invoking `EvidenceObligationVerifier`;
- deriving `missingEvidence`;
- deriving `protectedReadApprovalMissing`.

`ExecutionOutcome` should still own:

- final answer shaping from the evidence result;
- passing the obligation/result into `EvidenceContainmentAnswerGuard`;
- protected-read answer postcondition handling;
- outcome dominance;
- `TaskOutcome` assembly.

## Rejected Scope For T417

T417 must not move:

- `hasUnsupportedDocumentCapabilityLimit(...)`;
- `readOnlyToolLimitWithoutRuntimeAnswer(...)`;
- `failurePolicyStoppedWithoutMutation(...)`;
- `pendingActionObligationFailure(...)`;
- `OutcomeDominancePolicy`;
- any `AssistantTurnExecutor` compatibility answer-shaping helper;
- `EvidenceContainmentAnswerGuard` wording.

Those are adjacent, but not the same ownership unit.

## T417 Test Shape

Recommended RED/GREEN tests:

- null plan returns `EvidenceObligation.NONE` with a satisfied result;
- source evidence targets are preferred over expected targets;
- fallback loop evidence is synthesized from `LoopResult.toolNames()` and
  `readPaths()` when `toolOutcomes()` are absent;
- existing `toolOutcomes()` are used when present;
- protected-read approval missing is true only for
  `PROTECTED_READ_APPROVAL_REQUIRED` plus an unsatisfied result.

Recommended focused gate:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.EvidenceObligationAssessmentTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
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

After T416 integrates cleanly, start T417 from fresh `origin/v0.9.0-beta-dev`
and extract only `EvidenceObligationAssessment`.
