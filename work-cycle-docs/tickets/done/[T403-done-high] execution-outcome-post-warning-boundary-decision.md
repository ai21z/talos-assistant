# [T403-done-high] ExecutionOutcome Post-Warning Boundary Decision

Status: done
Priority: high
Date: 2026-05-24
Branch: `T403`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `eb6ffba9`
Predecessor: `T402`

## Scope

T403 inspects the post-T402 `ExecutionOutcome` shape and chooses the next
coherent runtime outcome ownership slice.

This is intentionally a no-code decision ticket. T402 already moved warning
construction to `TaskOutcomeWarningBuilder`; the next step must be selected from
current source evidence, not from momentum.

## Source Shape

Current source size:

```text
src/main/java/dev/talos/cli/modes/ExecutionOutcome.java: 1489 lines
src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java: 3177 lines
```

T402 removed direct warning construction from `ExecutionOutcome`, but
`ExecutionOutcome` still owns several separate outcome-truthfulness mechanisms:

- command outcome conclusion and command-result replacement text;
- evidence-obligation verification and missing-evidence containment;
- approved protected-read postcondition repair;
- static verification annotation/replacement text;
- verified changed-files summary selection;
- local trace outcome emission;
- orchestration between these mechanisms and `OutcomeDominancePolicy`.

## Evidence

### Command Outcome Truthfulness

`ExecutionOutcome` still owns command-specific result classification and
runtime replacement wording:

```text
ExecutionOutcome.commandConclusion(...)
ExecutionOutcome.commandFailureReplacement(...)
ExecutionOutcome.commandSuccessReplacement(...)
ExecutionOutcome.commandRequiredButNotRunReplacement()
ExecutionOutcome.unsupportedCommandNotAvailableReplacement()
ExecutionOutcome.commandSatisfiesVerifyOnlyRequest(...)
ExecutionOutcome.explicitCommandVerificationRequired(...)
ExecutionOutcome.unsupportedCommandVerificationRequest(...)
ExecutionOutcome.unsupportedPythonCommandExecutionRequest(...)
```

Call sites:

```text
ExecutionOutcome.fromToolLoop(...): command conclusion and command replacement
ExecutionOutcome.fromNoTool(...): command-required and unsupported-command replacement
```

Regression coverage already exists in `ExecutionOutcomeTest` for:

- failed command dominates model success prose;
- denied command dominates model success prose;
- successful verify command uses runtime-owned summary;
- successful command does not complete an unperformed mutation request;
- explicit command request without `talos.run_command` is blocked and sanitized;
- unsupported Python/pytest command claims are replaced by deterministic beta
  boundary wording.

This is a coherent owner because it is about command-result truthfulness, not
about static task verification, protected-read privacy, or generic evidence
containment.

### Static Verification Rendering

`ExecutionOutcome` also owns post-apply verification answer shaping:

```text
staticVerificationPassedAnnotation(...)
readbackOnlyVerificationAnnotation(...)
staticVerificationFailedAnnotation(...)
staticVerificationFailedReplacement(...)
partialStaticVerificationFailedAnnotation(...)
staticVerificationUnavailableAnnotation(...)
verifiedChangedFilesSummary(...)
```

This is a plausible future owner, but it is bigger than the command slice. It
mixes task-verifier status, changed-file summary rendering, mutating tool
outcome reporting, and workspace-operation readback wording.

### Evidence Containment And Protected Reads

`ExecutionOutcome` still owns missing-evidence and protected-read containment:

```text
verifyEvidence(...)
suppressDerivedContentForMissingEvidence(...)
protectedReadMissingEvidenceContainment(...)
suppressProtectedHistoryContentIfNeeded(...)
enforceApprovedProtectedReadPostcondition(...)
approvedProtectedReadEvidenceAnswer(...)
```

This is high-value but riskier. It touches privacy, protected path
classification, prior assistant-message scanning, current-turn approved read
evidence, and trace-side postcondition records. It should not be extracted as a
casual next slice.

### Trace Outcome Emission

`ExecutionOutcome.recordLocalTraceOutcome(...)` still emits verification,
warning, and task-outcome trace records. It should remain near the final
assembled `TaskOutcome` until the result-shaping slices are cleaner. Moving it
now would blur orchestration and side effects.

## Decision

The next implementation ticket should be:

```text
[T404] Extract command outcome renderer
```

T404 should create a focused runtime outcome component, likely:

```text
dev.talos.runtime.outcome.CommandOutcomeRenderer
```

The component should own only command-result truthfulness and exact command
replacement wording:

- identify the first failed/denied `talos.run_command` outcome;
- identify the first successful `talos.run_command` outcome;
- render failed/timed-out command replacement text;
- render denied command replacement text;
- render successful command summary with existing punctuation behavior;
- render explicit-command-required-but-not-run replacement text;
- render unsupported Python/pytest command replacement text;
- expose the existing command-required and unsupported-command classification
  helpers needed by `ExecutionOutcome`.

T404 must preserve exact wording, exact warning types, exact dominance behavior,
exact final-answer behavior, and exact trace behavior.

## Rejected Next Slices

Do not make T404 a static-verification renderer extraction yet. That slice is
larger and should happen only after command-result truthfulness is isolated.

Do not make T404 an evidence-containment/protected-read extraction. That area is
privacy-sensitive and should get its own decision ticket or carefully scoped
implementation ticket.

Do not move `OutcomeDominancePolicy` in T404. Dominance already has a dedicated
class, and command extraction should not rewrite the global completion-status
decision.

Do not move local trace emission in T404. Trace side effects should remain after
`TaskOutcome` assembly for now.

## T404 Check Shape

Recommended T404 implementation cycle:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.CommandOutcomeRendererTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.outcome.CommandOutcomeRendererTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

T404 should add focused renderer tests before implementation. The RED test
should fail because `CommandOutcomeRenderer` does not exist yet.

## Verification

T403 verification:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Results:

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; 1 actionable task executed).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: 13 executed, 1 up-to-date).

## Next Move

After T403 integrates, start T404 from fresh `origin/v0.9.0-beta-dev` and
extract only command outcome rendering. Do not move static verification,
evidence containment, protected-read postconditions, dominance policy, or trace
emission in the same ticket.
