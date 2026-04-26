# [done] Ticket: Read-Only Web Diagnostic Loop Short-Circuit
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/talos-read-only-web-diagnostics-static-grounding.md`

## Why This Ticket Exists

Installed verification after adding deterministic read-only web diagnostics
confirmed the final answer is now grounded, but the tool loop still ran to the
iteration cap first.

Observed transcript:

```text
[Used 10 tool(s): talos.list_dir, talos.retrieve, talos.grep | 10 iteration(s)] [2 failed]
[iteration limit reached]

I inspected the primary web files:
...
Static web diagnostics found:
- index.html: malformed closing tag `</button>` is missing `>`.
- index.html: malformed closing tag `</script>` is missing `>`.
- CSS likely uses bare element selectors where HTML defines classes:
  `calculator-container` should probably be `.calculator-container`

No files were changed.
```

The final answer is correct, but the runtime got there through an inefficient
read-only loop.

## Problem

For explicit read-only web diagnostics, Talos can already compute deterministic
static facts from the local workspace. Letting the model continue repeated
read-only tool calls until the generic iteration cap is noisy, slower, and makes
normal output look less disciplined.

## Goal

Stop or downgrade read-only web diagnostic loops earlier when deterministic
static diagnostics are available.

## Scope

### In scope

- Detect no-mutation web diagnostic turns where the loop has enough local facts
  or static diagnostics can be computed directly.
- Stop before the generic iteration cap and return the deterministic diagnostic.
- Preserve normal read-only inspection for non-web and non-diagnostic prompts.
- Add deterministic loop/e2e coverage for the current 10-iteration shape.

### Out of scope

- Mutating repair behavior.
- Browser execution.
- Shell/test-runner tools.
- Broad planner changes.

## Proposed Work

1. Add a narrow failure-policy or executor-side short-circuit for read-only web
   diagnostics after repeated read-only no-progress.
2. Prefer a central loop/failure policy signal over answer-string patching.
3. Reuse `StaticTaskVerifier.renderWebDiagnostics(...)` as the deterministic
   terminal answer when the short-circuit fires.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Manual:

- Run installed Talos in `local/manual-testing/qa-workspaces/broken-bmi-stale`.
- Ask the read-only diagnostic prompt.
- Confirm the final answer remains grounded and the turn does not hit the
  generic 10-iteration cap.

## Acceptance Criteria

- The grounded diagnostic remains correct.
- No files are changed and no approval is requested.
- The loop does not run to the generic iteration cap for this known shape.

## Completion Notes

Implemented on branch `ticket/talos-read-only-web-diagnostic-loop-short-circuit`.

- Added a shared `WebDiagnosticIntent` predicate for read-only web diagnostic
  requests.
- Added a central `ToolCallRepromptStage` short-circuit: when a read-only web
  diagnostic turn has invoked a tool and deterministic static diagnostics are
  available, the loop stops before another LLM reprompt.
- Kept the stop out of the failure-policy summary because this is a successful
  deterministic diagnostic terminal answer, not a failure.
- Added JSON scenario
  `33-read-only-web-diagnostics-short-circuit.json`.

Verification:

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.readOnlyWebDiagnosticsShortCircuit"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
pwsh tools/uninstall-windows.ps1 -Quiet
./gradlew.bat --no-daemon installDist
pwsh tools/install-windows.ps1 -Force -Quiet
```

Installed Talos verification against
`local/manual-testing/qa-workspaces/broken-bmi-stale` produced:

```text
[Used 1 tool(s): talos.retrieve | 1 iteration(s)]
Static web diagnostics found:
- index.html: malformed closing tag `</button>` is missing `>`.
- index.html: malformed closing tag `</script>` is missing `>`.
- CSS likely uses bare element selectors where HTML defines classes:
  `calculator-container` should probably be `.calculator-container`
No files were changed.
```
