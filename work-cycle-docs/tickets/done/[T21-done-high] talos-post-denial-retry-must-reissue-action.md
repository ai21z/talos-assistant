# [T21-done-high] Ticket: Post-Denial Retry Must Reissue Action
Date: 2026-04-27
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `work-cycle-docs/tickets/done/[T14-done-high] talos-repair-followup-after-incomplete-outcome.md`
- `work-cycle-docs/tickets/done/talos-post-denial-mutation-recovery.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`

## Why This Ticket Exists

T14 fixed the architectural split where a repair follow-up could resolve to an
apply-capable contract while the native tool surface remained read-only.

Manual branch review confirmed that invariant now holds, but also found that
live-model behavior is still not robust enough: after approval denial, a
natural retry can expose mutating tools yet fail to reissue the previous write
action.

This means Talos may appear ready to repair but still stall in a common user
flow.

## Problem

Manual failing flow:

```text
Create scripts.js with exactly this text: console.log("repair ok"); Use file tools; do not just show code.
n
nothing changed, try one more time
```

Observed:

- Turn 1 requested approval for `talos.write_file`.
- User denied approval.
- Retry turn trace showed:
  - `contract: FILE_CREATE`
  - `mutationAllowed=true`
  - mutating native tools exposed
- But the model answered:
  - `I'm sorry, but I cannot assist with that request.`
- No second write approval appeared.
- No file was created.

Manual evidence:

- `local/manual-testing/branch-review-repair-output.txt`
  - first approval around line 15
  - retry contract/tool surface around lines 48-51
  - no write call / refusal around line 44

Control check:

The exact T14 ticket prompt shape did pass:

```text
Create scripts.js with exactly this JavaScript line: const result = 'first attempt'; Use the file tool and do not just show code.
n
nothing changed, try one more time
y
```

Manual evidence:

- `local/manual-testing/branch-review-repair-t14-replication-output.txt`
  - second approval around line 45
  - `Created scripts.js` around line 61

So the contract/tool-surface invariant is fixed, but retry execution remains
too dependent on model interpretation of the prior denied action.

## Goal

Make post-denial retry behavior reliable enough that a bare retry phrase after
a denied mutating action causes Talos to reissue or strongly restate the prior
approved-safe action, rather than leaving the model to infer it from history.

## Scope

In scope:

- Detect retry turns after approval-denied mutation attempts.
- Preserve the previous failed/denied action context for the retry turn.
- Make the retry instruction explicit enough that the model reissues the prior
  tool call when the user asks to try again.
- Keep approval required for the retry.
- Keep status questions such as `did you make the changes?` verify-only.
- Add deterministic unit/e2e coverage and installed manual verification.

Out of scope:

- Automatically applying denied mutations without a fresh approval prompt.
- Bypassing approval.
- Adding background autonomy.
- Shell/browser/MCP/test-runner tools.
- Replaying arbitrary stale tool calls without checking the current user retry
  intent.

## Architecture Invariant

After a denied mutating tool call, a user retry phrase such as:

```text
nothing changed, try one more time
```

must lead to exactly one of these safe outcomes:

1. the same mutation intent is re-presented for approval,
2. the runtime refuses with a clear policy reason,
3. Talos asks a concise clarification because the previous action cannot be
   safely reconstructed.

It must not silently expose mutating tools and then produce a generic refusal or
read-only answer with no actionable path.

## Technical Analysis

Likely root seams:

- `TaskContractResolver.looksLikeRepairFollowUp(...)`
- `TaskContractResolver.inheritedRepairContract(...)`
- `AssistantTurnExecutor.resolveNoToolAnswer(...)`
- `AssistantTurnExecutor.mutationRequestRetryIfNeeded(...)`
- `ToolCallRepromptStage`
- session/history representation of `approval denied` outcomes
- `ToolCallLoop.ToolOutcome`

Current behavior after T14:

1. The retry turn can inherit the correct `FILE_CREATE` contract.
2. The native tool surface includes `write_file` and `edit_file`.
3. The trace is internally consistent.
4. The model can still fail to call the tool, because the retry prompt contains
   only the user's short retry phrase and general history. Some model runs
   reconstruct the prior action; others refuse or drift.

The likely fix should be deterministic at the harness layer, not just prompt
tone. Options to evaluate during implementation:

- Inject a compact system/developer instruction for post-denial repair turns:
  "The previous mutating tool call was denied; the user is retrying. Reissue
  the same requested action through tools, requiring approval again."
- Preserve a structured last-denied action summary and include it in the turn
  context.
- Add a bounded retry path when mutationAllowed=true and no tool call occurs,
  but only if the previous outcome explains a denied mutation and the current
  prompt is a repair retry.
- Do not auto-replay the tool call without model/tool-loop involvement unless a
  separate architecture ticket approves deterministic replay.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

- Unit test that a post-denial retry inherits mutationAllowed and receives a
  retry-specific instruction/context.
- Unit/e2e test where a scripted model initially returns no tool call on the
  retry and the runtime performs one bounded repair reprompt rather than
  accepting the no-tool refusal.
- Negative test: `did you make the changes?` after denial remains
  `VERIFY_ONLY` and does not retry mutation.

E2E:

- Scenario:
  - turn 1: model calls `write_file`, approval denied,
  - turn 2: user says `nothing changed, try one more time`,
  - model initially drifts/refuses or omits tool call,
  - expected runtime reprompt or contextualization causes `write_file` to be
    requested again,
  - approval is required again.

Manual:

Installed Talos:

```text
/session clear
/debug trace
Create scripts.js with exactly this text: console.log("repair ok"); Use file tools; do not just show code.
n
nothing changed, try one more time
a
did you make the changes?
```

Expected:

- first turn asks approval and denial causes no mutation,
- retry turn asks approval again,
- approved retry creates `scripts.js`,
- status question is `VERIFY_ONLY` and does not mutate.

## Acceptance Criteria

- Post-denial retry reliably reissues the previous safe mutation for approval
  or produces a clear structured reason why it cannot.
- It does not bypass approval.
- It does not mutate on status questions.
- Trace shows contract/tool-surface consistency.
- Manual retry with `console.log("repair ok");` passes.
- Focused tests, `e2eTest`, `check`, and installed manual verification pass
  before marking done.

## Current Code Read

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/core/llm/LlmClient.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/e2eTest/resources/scenarios/14-approval-denial-stops-loop.json`
- `src/e2eTest/resources/scenarios/48-repair-followup-after-incomplete-outcome-applies.json`

## Planned Tests

- Add focused `AssistantTurnExecutorTest` coverage where a post-denial retry initially receives a no-tool refusal, then the deterministic retry prompt causes a `write_file` call to execute.
- Add/confirm task-contract coverage that a status question after denial remains `VERIFY_ONLY`.
- Add a JSON e2e scenario with prior denied mutation history, current retry phrase, no-tool refusal, then a reissued `write_file`.

## Implementation Summary

- Updated the no-tool mutation retry gate in `AssistantTurnExecutor` to use the full history-aware `TaskContract` instead of latest-message-only mutation detection.
- Added retry prompt context that pins the previous mutation request when the current user message is a retry/repair follow-up.
- Preserved approval safety: denied mutations are not auto-applied, and retry execution still goes through normal `write_file` approval.
- Preserved status safety: status questions after denied mutations remain `VERIFY_ONLY` and do not trigger mutation retry.
- Added deterministic unit and JSON e2e coverage for no-tool post-denial retry recovery.

## Tests Run

Initial TDD red run:

- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`: FAIL as expected on `postDenialRepairFollowUpNoToolAnswerRetriesAndExecutesPriorWrite`.

Focused tests:

- `./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`: PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.postDenialRetryReissuesWrite" --no-daemon`: PASS

Broader runtime checks:

- `./gradlew.bat e2eTest --no-daemon`: PASS
- `./gradlew.bat check --no-daemon`: PASS

## Work-Test-Cycle Loop Used

Inner dev loop. No candidate version was declared and no changelog entry was added for this per-ticket commit.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
cd local/manual-workspaces/T21
@('/session clear','/debug trace','Create scripts.js with exactly this text: console.log("repair ok"); Use file tools; do not just show code.','n','nothing changed, try one more time','a','did you make the changes?','/q') | talos 2>&1 | Tee-Object -FilePath ..\..\manual-testing\T21-output.txt
```

Workspace:

- `local/manual-workspaces/T21/`

Model:

- `qwen2.5-coder:14b`

Prompts:

- `Create scripts.js with exactly this text: console.log("repair ok"); Use file tools; do not just show code.`
- `nothing changed, try one more time`
- `did you make the changes?`

Approval choice:

- First approval: `n`
- Retry approval: `a`

Observed tools:

- First turn: `talos.write_file` attempted and denied.
- Retry turn: `talos.write_file` reissued and approved.
- Status turn: `talos.list_dir`, `talos.read_file`.

Files changed:

- `scripts.js` created only after the approved retry.

Output file:

- `local/manual-testing/T21-output.txt`

Pass/fail:

- PASS

Notes:

- First turn trace: `contract: FILE_CREATE mutationAllowed=true`; blocked by user approval denial.
- Retry turn trace: `contract: FILE_CREATE mutationAllowed=true`; approval was requested again and `scripts.js` was created.
- Status turn trace: `contract: VERIFY_ONLY mutationAllowed=false`; native tools were read-only only and no mutation occurred.

## Known Follow-Ups

- The retry prompt now pins the previous mutation request for repair follow-ups. It still does not auto-replay stale tool calls, which remains intentional.
