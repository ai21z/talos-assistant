# [T464-done-high] Extract Source-Evidence Before-Read Guard

## Status

Done.

## Scope

T464 extracts source-derived write-before-source-read diagnostic selection from
`ToolCallExecutionStage` into the existing `SourceDerivedEvidenceGuard` owner.

This is a behavior-preserving execution-lane extraction. It does not change
source-evidence exact coverage repair, approval behavior, protected/private
read handoff, mutation evidence, static-web full rewrite recovery, context
ledger capture, final answer wording, or tool execution.

## Source Shape

Before T464, `ToolCallExecutionStage` directly owned:

- source-derived mutation classification for `write_file` and `edit_file`;
- required source-read inventory from `TurnSourceEvidenceCapture` and
  `LoopState.pathsReadThisTurn`;
- source target path normalization for the before-read gate;
- exact user-facing diagnostic wording for writes blocked before approval.

After T464, `ToolCallExecutionStage` delegates diagnostic selection:

```text
SourceDerivedEvidenceGuard.requiredSourceEvidenceDiagnostic(
    LoopState state,
    TaskContract contract,
    ToolCall call,
    String pathHint
)
```

The stage keeps execution side effects:

- failure counters;
- `recordFailure(...)`;
- `ToolResult.fail(...)`;
- `emitToolResult(...)`;
- `SOURCE_EVIDENCE_BEFORE_DERIVED_WRITE` trace/action-obligation recording;
- failed `ToolOutcome` recording;
- result-message append;
- loop `continue`.

## Guardrails Preserved

T464 preserves:

- exact diagnostic wording:
  `Source-derived artifact write blocked before approval: ...`;
- source target ordering from the task contract;
- source-read evidence from both `TurnSourceEvidenceCapture.readPaths()` and
  `LoopState.pathsReadThisTurn`;
- `write_file` and `edit_file` alias classification through
  `ToolAliasPolicy.localCanonicalName(...)`;
- no approval request before required source evidence is read;
- no mutation before required source evidence is read;
- existing exact source-evidence coverage repair behavior after sources have
  been read.

T464 deliberately does not touch:

- `SourceDerivedEvidenceGuard.exactEvidenceCoverageDiagnostic(...)`;
- `SourceDerivedEvidenceGuard.repairedExactEvidenceWrite(...)`;
- `SourceEvidenceExactRepairPlanner`;
- compact mutation continuation;
- protected/private document policy;
- mutation evidence;
- final task outcome rendering.

## Tests

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --no-daemon
```

Failed before implementation because `RequiredSourceEvidenceDiagnostic` and
`requiredSourceEvidenceDiagnostic(...)` did not exist.

GREEN focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceDerivedEvidenceGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.sourceDerivedExactEvidenceWriteMissingSourcePhraseIsRepairedBeforeMutation" --tests "dev.talos.runtime.ToolCallLoopTest.mutationContinuationIncludesSourceEvidenceReadbacksForSourceDerivedWrite" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.SourceEvidenceExactRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*summarizeSourceIntoFileWithoutSourceReadDoesNotCreateUngroundedArtifact" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*summarizeSourceIntoFileSplitReadThenRetryPreservesSourceEvidence" --no-daemon
```

Passed after implementation.

Note: an attempted parallel run of multiple Gradle `test` tasks in the same
worktree hit a transient `build/test-results/test/binary/output.bin` deletion
collision. The same focused checks passed when rerun sequentially.

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
