# [T467-done-high] Extract Redundant Read Suppression Guard

## Status

Done.

## Scope

T467 extracts duplicate read-only call suppression from
`ToolCallExecutionStage` into `RedundantReadSuppressionGuard`.

This is a behavior-preserving execution-lane extraction. It does not change
tool execution, strict-mode behavior, approval behavior, source-evidence
behavior, append-line behavior, edit retry safety, protected/private content
handoff, context ledger capture, mutation evidence, post-result static-web
repair state, or final answer wording.

## Source Shape

Before T467, `ToolCallExecutionStage` directly decided whether a read-only tool
call should be suppressed when the same successful read signature had already
been gathered and the workspace had not mutated.

After T467, `ToolCallExecutionStage` delegates the decision:

```text
RedundantReadSuppressionGuard.decision(
    ToolCall call,
    LoopState state,
    boolean strict
)
```

The guard returns a decision record with:

- normalized read signature;
- exact suppression diagnostic.

The stage keeps execution lifecycle side effects:

- incrementing `state.cushionFiresRedundantRead`;
- formatting the tool-result wrapper;
- appending the result message;
- logging the suppressed signature;
- deciding loop `continue`.

## Guardrails Preserved

T467 preserves:

- exact redundant-read nudge wording:
  `You already gathered this information and the workspace has not changed since then. Answer the user's question now using the evidence you already have.`;
- normal mode suppresses duplicate read-only calls;
- strict mode re-executes duplicate read-only calls;
- read suppression is disabled after a mutation starts;
- mutating calls are never suppressed by this guard;
- suppressed duplicate reads still count through `cushionFiresRedundantRead`;
- terminal read-only stop and reprompt budget behavior.

T467 deliberately does not touch:

- `SourceDerivedEvidenceGuard`;
- `AppendLinePreApprovalGuard`;
- `EditFilePreApprovalGuard`;
- protected/private content handoff;
- mutation evidence;
- context ledger capture;
- post-result static-web full rewrite detection;
- final answer wording.

## Tests

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --no-daemon
```

Failed before implementation because `RedundantReadSuppressionGuard` did not
exist.

GREEN focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.RedundantReadSuppressionGuardTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.StrictModeScenariosTest.redundantReadSuppressionDifference" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*redundant*" --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswerTest" --no-daemon
```

Passed after implementation.

## Verification

Required closeout gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

Run before PR.
