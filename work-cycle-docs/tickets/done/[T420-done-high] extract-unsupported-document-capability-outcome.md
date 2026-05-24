# [T420-done-high] Extract Unsupported Document Capability Outcome

## Status

Done.

## Scope

T420 extracts unsupported document capability detection out of
`ExecutionOutcome` and into runtime outcome ownership.

The ticket intentionally does not move:

- unsupported document answer wording;
- `AssistantTurnExecutor.overrideUnsupportedDocumentClaimsIfNeeded(...)`;
- outcome dominance policy;
- protected-read answer guards;
- evidence containment;
- static verification dispatch;
- warning construction.

## Change

Added:

```text
dev.talos.runtime.outcome.UnsupportedDocumentCapabilityOutcome
```

The new owner detects whether a `ToolCallLoop.LoopResult` contains a failed
canonical `talos.read_file` outcome with `ToolError.UNSUPPORTED_FORMAT`.

`ExecutionOutcome` now asks that outcome owner for the boolean fact it already
used:

```text
UnsupportedDocumentCapabilityOutcome.assess(loopResult).limited()
```

## Why This Is The Correct Boundary

Unsupported document capability detection is not CLI-mode orchestration. It is a
runtime outcome fact derived from tool-loop evidence. Keeping read-file alias
canonicalization and `ToolError.UNSUPPORTED_FORMAT` inspection inside
`ExecutionOutcome` forced the CLI outcome facade to know low-level tool outcome
details.

The new class is deliberately small. It owns only detection. Final wording,
warning construction, and dominance decisions stay with their existing owners.

## Behavior

No behavior or wording changes are intended.

Existing unsupported document behavior remains:

- unsupported document read outcomes are advisory;
- unsupported document warnings are still emitted;
- final answer correction remains handled by existing answer-shaping code;
- trace outcome classification remains unchanged.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.UnsupportedDocumentCapabilityOutcomeTest" --no-daemon
```

Failed because `UnsupportedDocumentCapabilityOutcome` did not exist.

GREEN focused:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.UnsupportedDocumentCapabilityOutcomeTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Passed.

Required gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Passed.

## Next

After T420 integrates cleanly, inspect `ExecutionOutcome` again before choosing
T421. Do not assume the next ticket is another extraction.
