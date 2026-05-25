# [T463-done-high] Extract Append-Line Pre-Approval Guard

## Status

Done.

## Scope

T463 extracts append-line full-write preservation diagnostics from
`ToolCallExecutionStage` into `AppendLinePreApprovalGuard`.

This is a behavior-preserving execution-lane extraction. It does not change
approval behavior, tool execution, protected/private read handoff, source
evidence behavior, context ledger capture, mutation evidence, static-web repair
state, trace wording, final outcome wording, or compact repair behavior.

## Source Shape

Before T463, `ToolCallExecutionStage` directly owned append-line pre-approval
diagnostic selection and helper logic:

- task expectation lookup through `TaskExpectationResolver`;
- append-line target matching;
- same-turn complete readback lookup;
- readback body parsing;
- line-ending normalization;
- exact preservation comparison;
- missing-read and failed-preservation diagnostic wording.

After T463, `ToolCallExecutionStage` delegates only the diagnostic decision:

```text
AppendLinePreApprovalGuard.diagnostic(
    ToolCall call,
    LoopState state,
    TaskContract contract,
    String pathHint
)
```

The stage keeps execution lifecycle side effects:

- incrementing failure counters;
- recording the failure signature;
- creating and emitting the failed `ToolResult`;
- recording `APPEND_LINE_WRITE_PRESERVATION`;
- adding the failed `ToolOutcome`;
- appending formatted tool result output;
- preserving loop control.

## Guardrails Preserved

T463 preserves:

- exact missing-read diagnostic wording:
  `append-line write_file for ... requires complete same-turn read evidence before approval.`;
- exact failed-preservation diagnostic wording:
  `append-line write_file for ... does not preserve the complete same-turn readback and append exactly ...`;
- alias handling through `ToolAliasPolicy.localCanonicalName(...)`;
- target matching through `TaskExpectationResolver.resolve(...)`;
- same line-ending normalization;
- optional terminal newline acceptance;
- no approval request for invalid append-line full writes;
- no mutation on invalid append-line full writes;
- existing compact repair behavior after the pre-approval failure.

T463 deliberately does not touch:

- source-evidence guards;
- full-rewrite repair edit blocking;
- stale edit reread blocking;
- duplicate edit suppression;
- redundant read suppression;
- protected/private read handoff;
- context ledger capture;
- mutation evidence;
- static-web full rewrite recovery;
- final answer wording.

## Tests

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.AppendLinePreApprovalGuardTest" --no-daemon
```

Failed before implementation because `AppendLinePreApprovalGuard` did not
exist.

GREEN focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.AppendLinePreApprovalGuardTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.appendLineFullWriteThatDoesNotPreserveReadbackIsRejectedBeforeApproval" --tests "dev.talos.runtime.ToolCallLoopTest.appendLinePreapprovalFailureUsesCompactRepairWithReadbackBeforeApproval" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.TargetReadbackCompactRepairPlannerTest" --no-daemon
```

Passed after implementation.

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Passed before PR.
