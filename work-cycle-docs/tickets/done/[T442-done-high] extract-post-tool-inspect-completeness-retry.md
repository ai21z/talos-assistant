# [T442-done-high] Extract Post-Tool Inspect-Completeness Retry

## Status

Done.

## Scope

T442 extracts the post-tool inspect-completeness retry from
`AssistantTurnExecutor` into `InspectCompletenessRetry`.

This is an ownership refactor. It preserves runtime behavior and does not
change missing-mutation retry, read-only no-tool inspection retry,
no-tool grounding retry, answer wording, outcome dominance, or static-web
diagnostic rendering.

## Change

Added:

```text
dev.talos.cli.modes.InspectCompletenessRetry
```

`InspectCompletenessRetry` now owns:

- missing primary and linked-script read selection for post-tool inspect retry;
- protected-path filtering for linked-script retry targets;
- post-tool inspect retry eligibility gates;
- corrective prompt construction;
- one supplied model retry call;
- optional tool-loop re-entry;
- read-only retry evidence merge and summary preservation.

`AssistantTurnExecutor` keeps package-visible compatibility wrappers for
existing tests and delegates through a supplied chat function so the model call
still flows through the existing executor `chatFull(...)` path.

## Guardrails

Preserved:

- directory-listing bypass;
- inspect-first and workspace-evidence eligibility behavior;
- linked-script protected/external target filtering;
- exact corrective prompt wording;
- retry message append order;
- text-tool-call detection behavior;
- retry loop execution behavior;
- merged read-path order and normalization;
- merged tool-name and tool-outcome order;
- retry final answer, retry messages, and retry failure decision;
- single visible `[Used ...]` summary after a merged inspect retry.

Not changed:

- `mutationRequestRetryIfNeeded(...)`;
- compact mutation retry prompt/tool-surface helpers;
- mutation retry trace recording;
- mutation retry evidence merge;
- `ReadOnlyInspectionRetry`;
- `NoToolGroundingRetry`;
- `ToolCallLoop` semantics.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.InspectCompletenessRetryTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable InspectCompletenessRetry
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.InspectCompletenessRetryTest" --no-daemon
```

Wider focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.InspectCompletenessRetryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ReadOnlyInspectionRetryTest" --tests "dev.talos.cli.modes.NoToolGroundingRetryTest" --no-daemon
```

## Full Verification

Run before merge:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T442 retry/orchestration shape before choosing T443.

The likely next question is whether the remaining missing-mutation retry can
safely lose its compact prompt/tool-surface envelope, but that should be
confirmed from source before code moves.
