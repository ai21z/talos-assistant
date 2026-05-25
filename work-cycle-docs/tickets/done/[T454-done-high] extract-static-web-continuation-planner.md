# [T454-done-high] Extract Static Web Continuation Planner

## Status

Done.

## Scope

T454 extracts static-web continuation planning from `ToolCallRepromptStage` into
`dev.talos.runtime.toolcall.StaticWebContinuationPlanner`.

This ticket does not change runtime behavior, continuation wording, verifier
problem wording, retry names, tool narrowing, required-tool controls, pending
action obligation semantics, final answer shaping, or generic failure-policy
ordering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `efe2f8ac`.

| Item | Measurement |
|---|---:|
| Candidate version | `talosVersion=0.9.9` |
| `ToolCallRepromptStage.java` after extraction | 1987 lines |
| `StaticWebContinuationPlanner.java` | 545 lines |
| `StaticWebContinuationPlannerTest.java` | 211 lines |
| Architecture baseline | 0 |

## Changes

- Added `StaticWebContinuationPlanner`.
- Added `StaticWebContinuationPlanner.Plan` so static-web continuation returns
  messages, narrowed tools, request controls, retry name, optional pending
  action obligation, and missing target details.
- Moved directory-only continuation prompt construction and tool narrowing into
  the planner.
- Moved static verification failure continuation prompt construction, missing
  target inference, linked asset inference, static verification snapshot
  creation, and pending-obligation planning into the planner.
- Kept `ToolCallRepromptStage` responsible for loop placement, applying the
  pending obligation, invoking `chatReprompt(...)`, and stopping when static
  verification already passes.
- Left unrelated repair, source-evidence, expected-target, compact mutation,
  compact read-only, terminal read-only, and failure-policy lanes untouched.

## Behavior Preserved

- Directory-only static-web creation still continues to actual file writes.
- Verification failure after a partial web file write still continues to the
  missing CSS/JavaScript assets.
- Missing linked assets are still inferred from mutated HTML.
- Already satisfied small-web mutation targets are excluded from missing-target
  continuations.
- `static-web-directory-only-continuation` and
  `static-web-verification-continuation` retry names are unchanged.
- The existing `static-web-directory-only-continuation` debug tag is preserved
  for both continuation control paths.

## Verification

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest" --no-daemon
```

Failed before implementation because `StaticWebContinuationPlanner` did not
exist.

GREEN and focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationDirectoryOnlyMutationContinuesToFileWrites" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetScopeBlockedMkdirForStaticWebCreationRepromptsToExactFiles" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationHtmlReferencingMissingAssetsContinuesToAssetWrites" --tests "dev.talos.runtime.ToolCallLoopTest.staticWebCreationMissingAssetContinuationRejectsRepeatedSatisfiedTargetRewrite" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolCallRepromptStageTest" --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest" --no-daemon
```

All passed.

Final local gate:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

All passed. `git diff --check` reported only the existing line-ending warning
for `ToolCallRepromptStage.java`.

## Next Move

After T454 is merged and beta push CI is clean, inspect the post-T454
`ToolCallRepromptStage` shape before choosing T455. Do not assume the next
ticket is another implementation extraction.
