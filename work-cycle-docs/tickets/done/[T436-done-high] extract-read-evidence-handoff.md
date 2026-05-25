# [T436-done-high] Extract Read Evidence Handoff

## Status

Done.

## Scope

T436 implements the T435 decision:

```text
[T436] Extract read evidence handoff
```

This ticket moves only deterministic read-evidence handoff and partial
read-evidence recovery into:

```text
dev.talos.cli.modes.ReadEvidenceHandoff
```

## What Changed

`ReadEvidenceHandoff` now owns:

- `unsupportedCapabilityPreflightIfNeeded(...)`;
- `readEvidenceHandoffIfNeeded(...)`;
- `readEvidenceRecoveryForPartialTargetsIfNeeded(...)`;
- deterministic `talos.read_file` tool-call rendering;
- read-evidence target matching;
- denied-outcome blocking for partial read-evidence recovery;
- handoff loop result packaging.

`AssistantTurnExecutor` keeps package-private compatibility wrappers for the
same handoff methods. The wrappers normalize the current turn plan exactly as
before, then delegate to `ReadEvidenceHandoff`.

## Why This Owner

This owner stays in `dev.talos.cli.modes` because it executes the turn's
configured `ToolCallLoop` through CLI `Context`.

It is not runtime policy and not outcome rendering:

- `EvidenceGate` still owns pure obligation and target selection;
- `ReadEvidenceHandoff` executes deterministic read handoff for those targets;
- `AssistantTurnExecutor` still composes the handoff result into the turn flow.

## What Did Not Change

This ticket intentionally did not change:

- `EvidenceGate` obligation selection;
- protected-read explicit-intent handling;
- unsupported capability classification;
- `talos.read_file` JSON shape;
- `ToolCallLoop` execution behavior;
- mutation retry;
- read-only inspection retry;
- inspect-completeness retry;
- no-tool grounding retry;
- static-web answer overrides;
- final answer wording;
- outcome dominance policy.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadEvidenceHandoffTest" --no-daemon
```

Expected failure:

```text
cannot find symbol: variable ReadEvidenceHandoff
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.ReadEvidenceHandoffTest" --no-daemon
```

Passed after adding `ReadEvidenceHandoff` and delegating from
`AssistantTurnExecutor`.

Focused regression coverage also passed:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.cli.modes.ReadEvidenceHandoffTest" `
  --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" `
  --tests "dev.talos.runtime.policy.EvidenceGateTest" `
  --tests "dev.talos.runtime.policy.EvidenceObligationVerifierTest" `
  --no-daemon
```

## Full Verification

Passed:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Correct Move

After T436 integrates cleanly, inspect the remaining retry/orchestration shape
before choosing another implementation. Do not jump into mutation retry,
read-only inspection retry, inspect-completeness retry, or no-tool grounding
retry without a fresh boundary decision.
