# [T541-done-high] Extract Tool Loop Final Answer Finalizer

Status: done
Priority: high
Date: 2026-05-27
Branch: `T541`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `222fdba2`
Predecessor: `T540`

## Scope

T541 implements the final-answer finalization boundary selected by T540.

The goal is ownership extraction only. Runtime behavior, final-answer wording,
redaction policy, parser behavior, suspicious-HTML stripping, iteration-limit
wording, and `LoopResult` field population must remain unchanged.

## Implementation

Added:

- `ToolLoopFinalAnswerFinalizer`
- `ToolLoopFinalAnswerFinalizerTest`

Moved out of `ToolCallLoop`:

- unresolved tool-call continuation fallback text;
- unfinished tool payload suppression predicate;
- iteration-limit final-answer notice application;
- final answer tool-call stripping;
- final answer suspicious HTML stripping;
- protected-content redaction when content was withheld from model context.

Preserved in `ToolCallLoop`:

- parse/execute/reprompt orchestration;
- iteration-limit detection and logging;
- `LoopResult` assembly;
- counters, path sets, failure decisions, and tool outcomes.

## Explicit Non-Changes

T541 does not change:

- final-answer wording;
- unresolved continuation fallback wording;
- iteration-limit suffix wording;
- `ToolCallParser` behavior;
- `Sanitize` behavior;
- `ProtectedContentPolicy` behavior;
- protected/private model-context handoff behavior;
- compact mutation continuation;
- compact read-only evidence continuation;
- normal reprompt result application;
- trace wording.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolLoopFinalAnswerFinalizerTest" --no-daemon
```

- Failed before implementation because `ToolLoopFinalAnswerFinalizer` did not
  exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolLoopFinalAnswerFinalizerTest" --no-daemon
```

- Passed after adding the owner.

Focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.ToolLoopFinalAnswerFinalizerTest" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

- Passed.

Final gate:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T541 merges and beta push CI passes, inspect the post-finalizer
tool-loop shape before selecting T542.

Do not assume the next ticket is another `ToolCallLoop` extraction. The likely
candidate is a short closeout/decision ticket for the response/final-output
lane, but it should be chosen from current source after T541 lands.
