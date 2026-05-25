# [T438-done-high] Extract Read-Only Inspection Retry

## Status

Done.

## Scope

T438 extracts the no-tool read-only inspection retry path from
`AssistantTurnExecutor` into `ReadOnlyInspectionRetry`.

This is an ownership refactor. It does not change runtime behavior, outcome
wording, retry prompt wording, or tool-loop semantics.

## Change

Added:

```text
dev.talos.cli.modes.ReadOnlyInspectionRetry
```

`ReadOnlyInspectionRetry` now owns:

- read-only workspace-evidence retry eligibility;
- corrective retry prompt construction;
- retry message append order;
- one supplied model retry call;
- optional tool-loop re-entry when the retry emits tool calls;
- retry result answer/summary handoff.

`AssistantTurnExecutor` keeps package-visible compatibility wrappers and
delegates to the new owner through a supplied chat function so existing provider
control, context fallback, and tool-surface behavior still flow through the
current executor path.

## Guardrails

Preserved:

- general read-only retry prompt wording;
- directory-listing retry wording;
- explicit command-verification retry wording;
- fallback `any obvious primary text files` wording;
- null/blank answer behavior;
- text/native tool-call detection;
- retry tool-loop execution behavior;
- returned answer, loop result, and extra-summary semantics.

Not changed:

- direct read-evidence handoff;
- missing-mutation retry;
- inspect-completeness retry;
- no-tool grounding retry;
- final answer shaping;
- streaming branch behavior;
- native tool-surface selection.

## Tests

RED was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadOnlyInspectionRetryTest" --no-daemon
```

Expected compile failure:

```text
cannot find symbol
  symbol:   variable ReadOnlyInspectionRetry
```

GREEN focused verification passed after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadOnlyInspectionRetryTest" --no-daemon
```

Wider focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadOnlyInspectionRetryTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.core.llm.AssistantTurnExecutorMutationRetryToolSurfaceTest" --no-daemon
```

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

Inspect the post-T438 retry/orchestration shape before selecting T439. Do not
automatically extract missing-mutation retry, inspect-completeness retry, or
no-tool grounding retry without source inspection.
