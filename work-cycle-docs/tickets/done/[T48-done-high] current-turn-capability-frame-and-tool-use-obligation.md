# [T48-done-high] Current-turn capability frame and tool-use obligation

Status: done
Priority: high

## Context

Installed Talos 0.9.8 correctly resolved a natural website creation prompt as
`FILE_CREATE` with `mutationAllowed=true` and exposed `talos.write_file` /
`talos.edit_file`, but the live model still answered that it could not access
or modify the local filesystem and offered snippets instead of using tools.

This is not a BMI-specific classifier bug. The task contract and native tool
surface were correct. The missing layer is a current-turn runtime capability
frame plus a post-model obligation check.

## Goal

Make current-turn tool/access capability a runtime invariant. For each turn,
Talos should derive the task contract, phase, visible tool surface, action
obligation, current-turn capability frame, and post-model response obligation
check.

For mutation-capable turns, the model must be told near the current user
message that approved file changes are possible through the visible file tools.
If it still returns a no-tool capability denial or snippet-only answer, Talos
must retry once or return a deterministic no-action explanation that does not
repeat the false denial.

## Non-Goals

- No BMI-specific phrase patch.
- No shell, browser, MCP, or multi-agent behavior.
- No weakening of privacy, directory-listing, read-only, approval, permission,
  checkpoint, verification, trace, or repair policy.
- No LLM classifier for safety-critical decisions.
- No version bump or changelog update.

## Implementation Notes

- Prefer focused policy/helper classes under `dev.talos.runtime.policy`.
- Preserve deterministic behavior.
- Keep the current TaskContract and NativeToolSpecPolicy as the authority for
  what tools are visible.
- Inject the current-turn capability frame near the current user request, not
  buried before history.
- Reuse normal ToolCallLoop execution for retry tool calls.

## Acceptance Criteria

- Capability/onboarding prompts are answered deterministically without tools and
  mention approved file changes.
- Mutation-capable turns receive a current-turn frame naming mutation tools and
  the mutating tool obligation.
- A no-tool mutation capability denial is not shown as final.
- If a retry emits write/edit tool calls, they run through the normal approval,
  permission, checkpoint, and verification path.
- If retry still refuses, Talos returns a deterministic runtime-grounded
  incomplete/no-action answer.
- Directory listing remains list-only.
- Small talk/privacy prompts expose no tools.
- Read-only/formatting-negation/protected-path behavior remains unchanged.

## Tests / Evidence

Implemented:

- Focused policy tests for action obligation derivation.
- Executor tests for no-tool mutation deflection retry, deterministic no-action
  failure, and current-turn frame placement.
- Unified mode tests for deterministic capability prompts and mutation-frame
  tool-surface alignment.
- Slash-command trace rendering test for action-obligation summaries.
- JSON e2e scenarios:
  - `73-mutation-create-no-tool-deflection-retries.json`
  - `74-mutation-create-no-tool-deflection-fails-closed.json`
- Manual installed Talos check with `qwen2.5-coder:14b`.

## Work-Test Cycle Notes

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

Focused tests:

- `./gradlew.bat test --tests "dev.talos.runtime.policy.ActionObligationPolicyTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.prompt.PromptInspectorTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon` - PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.mutationCreateNoToolDeflectionRetries" --no-daemon` - PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.mutationCreateNoToolDeflectionFailsClosed" --no-daemon` - PASS

Full gates:

- `./gradlew.bat test --no-daemon` - PASS
- `./gradlew.bat e2eTest --no-daemon` - PASS
- `./gradlew.bat check --no-daemon` - PASS
- `./gradlew.bat qodanaNativeFreshLocal --no-daemon` - PASS, 0 applied-profile problems after fixing three new constant-value findings in the current-turn injection helper.
- `./gradlew.bat talosQualitySummaries --no-daemon` - PASS

One parallel focused Gradle run failed with `Unable to delete directory
build\test-results\test\binary` because two test tasks were writing the shared
test-results directory at the same time. The affected test was rerun
sequentially and passed.

## Implementation Summary

- Added `ActionObligationPolicy`, `CurrentTurnCapabilityFrame`,
  `CapabilityAnswerPolicy`, and `ResponseObligationVerifier` under
  `dev.talos.runtime.policy`.
- Added deterministic capability/onboarding answers that do not inspect the
  workspace and explicitly mention approved file changes.
- Injected a current-turn capability frame near the latest user request using
  the same resolved `TaskContract`, phase, and visible native tool surface used
  by execution.
- Added mutation-response obligation checking: a mutation-capable turn that
  receives a no-tool capability denial is retried once with a stronger
  current-turn frame; if the retry still emits no tools, Talos returns a
  deterministic no-action answer instead of surfacing the false denial.
- Recorded action-obligation events in local trace and rendered the latest
  action-obligation summary in `/last trace`.
- Added deterministic e2e scenarios for retry-success and retry-fail-closed
  paths.

## Manual Talos Check Result

Command:
`pwsh .\tools\uninstall-windows.ps1 -Quiet`; `./gradlew.bat clean installDist --no-daemon`; `pwsh .\tools\install-windows.ps1 -Force -Quiet`; installed `talos.bat`

Workspace:
`local/manual-workspaces/T48-round3/`

Model:
`qwen2.5-coder:14b`

Prompt:
`hey`; `Who are you?`; `What can you help me with?`; `/debug trace`; `I want to create a modern BMI calculator website to use! Can you make it?`; `/last trace`

Approval choice:
`a` when `talos.write_file` approval was requested

Observed tools:
`talos.write_file` for `index.html`, `talos.write_file` for `bmi.js`, and `talos.read_file` for `index.html`

Files changed:
`index.html`, `bmi.js` inside the manual workspace

Output file:
`local/manual-testing/T48-output-round3.txt`

Pass/fail:
PASS for T48. The model did not produce a final false filesystem-denial answer; the mutation turn exposed and used write tools; approval and checkpointing remained active; `/last trace` showed `Action obligation: MUTATING_TOOL_REQUIRED`; final verification failure was reported truthfully.

Notes:
The live model still produced an incomplete web surface (`index.html` and
`bmi.js`, no stylesheet or `scripts.js`), so static web coherence failed
truthfully. That remains a T47/cross-file web repair competence follow-up, not
a T48 blocker.

## Known Risks

- Overcorrecting no-tool mutation responses could suppress a legitimate narrow
  clarification. Keep the first version conservative and task-contract based.
- The current executor already has several truth/retry layers. Avoid a broad
  rewrite in this ticket.

## Known Follow-Ups

- T47 remains open for cross-file web repair coherence after full writes.
- A backend-specific tool-use instruction profile for local Ollama/Qwen may be
  useful later, but was intentionally not implemented in T48.

## Commit

Commit hash: recorded in the final handoff. The exact self-referential hash
cannot be embedded into the same commit without changing that commit hash.
