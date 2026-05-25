# [T468-done-high] Extract Tool Mutation Evidence Factory

## Status

Done.

## Scope

T468 extracts mutation-evidence construction from `ToolCallExecutionStage` into
`ToolMutationEvidenceFactory`.

This is a behavior-preserving execution-lane extraction. It does not change
tool execution, approval behavior, pre-approval guards, redundant read
suppression, protected/private content handoff, context ledger capture,
post-result static-web recovery state, verifier policy, outcome wording, or
final answer wording.

## Source Shape

Before T468, `ToolCallExecutionStage` directly owned a private helper cluster
that built `ToolCallLoop.MutationEvidence`:

- exact-edit replacement evidence from `talos.edit_file`;
- full-write replacement evidence from `talos.write_file` when a complete
  same-path readback was available;
- complete readback parsing from line-numbered `read_file` output;
- fallback to `MutationEvidence.none()` for read-only, malformed, missing, or
  truncated evidence.

After T468, `ToolCallExecutionStage` delegates construction:

```text
ToolMutationEvidenceFactory.from(
    ToolCall call,
    LoopState state,
    String pathHint
)
```

The stage still decides when evidence is attached:

```text
result.success() ? ToolMutationEvidenceFactory.from(...) : null
```

## Guardrails Preserved

T468 preserves:

- exact-edit evidence kind `EXACT_EDIT_REPLACEMENT`;
- full-write evidence kind `FULL_WRITE_REPLACEMENT`;
- alias handling through `ToolAliasPolicy.localCanonicalName(...)`;
- complete-readback requirement for full-write replacement evidence;
- rejection of truncated or non-line-numbered readback bodies;
- missing `new_string`, empty `old_string`, and non-mutation calls returning
  `MutationEvidence.none()`;
- existing verifier consumers of mutation evidence.

T468 deliberately does not touch:

- `SourceDerivedEvidenceGuard`;
- `AppendLinePreApprovalGuard`;
- `EditFilePreApprovalGuard`;
- `RedundantReadSuppressionGuard`;
- protected/private content handoff;
- context ledger capture;
- post-result static-web full rewrite detection;
- verification dominance or final outcome selection.

## Tests

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --no-daemon
```

Failed before implementation because `ToolMutationEvidenceFactory` did not
exist.

GREEN focused checks:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.*exact*" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.*fullWrite*" --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
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
