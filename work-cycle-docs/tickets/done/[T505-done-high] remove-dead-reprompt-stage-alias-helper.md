# [T505-done-high] Remove Dead Reprompt Stage Alias Helper

## Status

Done.

## Scope

T505 removes the unused alias-canonicalization helper from
`ToolCallRepromptStage`.

This ticket preserves runtime behavior. It does not change reprompt ordering,
tool alias policy, tool-surface narrowing, prompt wording, continuation
planning, approval handling, failure policy, trace behavior, protected-path
behavior, or verification behavior.

## Changes

- Removed the unused private `canonicalToolName(...)` helper from
  `ToolCallRepromptStage`.
- Removed the now-unneeded `dev.talos.tools.ToolAliasPolicy` import from
  `ToolCallRepromptStage`.
- Added an ownership test proving the reprompt stage no longer imports
  `ToolAliasPolicy` or declares `canonicalToolName(...)`.

Canonical tool-name handling remains in the classes that actually need it,
including tool-call support, compact continuation, terminal read-only answer
selection, directory-listing evidence, static-web continuation planning, and
target-readback compact repair planning.

## RED/GREEN Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDoesNotOwnAliasCanonicalization" --no-daemon
```

Observed failure before production deletion:

```text
ToolCallRepromptStageTest > repromptStageDoesNotOwnAliasCanonicalization() FAILED
```

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest.repromptStageDoesNotOwnAliasCanonicalization" --no-daemon
```

Result: passed.

Focused regression verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

Result: passed.

## Full Verification

Run before commit:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

Result: all passed. `git diff --check` emitted only the known line-ending
warnings for `ToolCallRepromptStage.java` and `ToolCallRepromptStageTest.java`.

## Next Inspection

After T505, inspect `ToolCallRepromptStage` again before extracting anything
else. The remaining candidates still affect behavior-sensitive paths:

- post-mutation continuation/skip selection;
- temporary repair/progress/current-task message overlay and cleanup;
- chat reprompt execution and engine-error fallback wording.

Do not extract one of those branches without a fresh decision ticket and
wording/cleanup regression tests.
