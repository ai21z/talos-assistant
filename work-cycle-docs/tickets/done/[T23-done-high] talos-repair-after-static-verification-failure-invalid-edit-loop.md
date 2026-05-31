# [T23-done-high] Ticket: Repair After Static Verification Failure Must Avoid Invalid Edit Loops
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- work-cycle-docs/new-work.md
- docs/architecture/talos-harness-source-of-truth.md
- docs/architecture/talos-harness-plan.md
- work-cycle-docs/tickets/done/[T12-done-high] talos-pre-approval-mutating-required-args.md
- work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md
- work-cycle-docs/tickets/done/[T21-done-high] talos-post-denial-retry-must-reissue-action.md

## Why This Ticket Exists

T16 gives Talos a useful static verifier for web tasks. Manual testing showed the next failure mode: after static verification tells Talos exactly what is missing, the repair turn can enter an invalid `edit_file` loop and stop without fixing anything.

The guardrails are working, but task completion still fails because the assistant does not recover to a safer write strategy.

## Problem

Reproduced transcript:

- `local/manual-testing/deep-review/bmi-empty-c-repair-transcript.txt`

Prompt after partial BMI creation:

```text
Fix the remaining static verification problems now. Link scripts.js from index.html and add a calculate button that calls the BMI logic. Use file tools and do not just show code.
```

Observed:

- Trace: `contract: FILE_CREATE mutationAllowed=true verificationRequired=true`.
- Mutating tools were exposed.
- Talos attempted `edit_file` with invalid or placeholder arguments:
  - empty `old_string`
  - placeholder `new_string` such as `<head>` and `<form>`
  - repeated failed edit against `index.html`
- Failure policy stopped the loop.
- No file changed.

This is better than approving invalid edits, but it is still poor operator behavior. Once the model cannot produce a valid exact-string edit after reading the file, Talos should either:

- force a bounded re-read + exact replacement retry, or
- nudge the model to use `write_file` for the whole target file, or
- stop with a deterministic blocked outcome that explains the next safe action.

## Goal

Repair turns after static verification failure should not churn through invalid `edit_file` calls. Talos should recover to a safer strategy or stop with a more actionable, deterministic reason.

## Scope

In scope:
- Detect repeated invalid edit attempts for the same path in a repair turn.
- Prefer a bounded retry instruction that says to re-read the file and either use exact `old_string` or overwrite the target file with `write_file`.
- Keep pre-approval validation strict.
- Add deterministic tests for the invalid-edit repair loop.

Out of scope:
- Browser execution.
- New shell/test-runner tools.
- Broad planning architecture.
- Weakening placeholder guards.

## Proposed Work

- Extend failure-policy or reprompt-stage handling for repeated invalid `edit_file` arguments after a repair request.
- Ensure the model is given a precise recovery instruction once, not an unlimited retry.
- Consider a deterministic post-failure answer if no valid tool call is produced.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopP0Test.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused unit test with scripted model:
  - initial static verification failure in history,
  - repair prompt,
  - model emits invalid edit args,
  - Talos sends bounded recovery instruction or returns deterministic blocked outcome.
- E2E scenario for partial web app repair.
- Manual Talos test in BMI workspace:
  - create partial BMI app,
  - ask to fix remaining verifier problems,
  - confirm Talos either repairs or gives a truthful actionable block.

## Acceptance Criteria

- Invalid edit args still do not reach approval.
- Repeated invalid edit attempts do not produce vague prose or raw tool dumps.
- Talos does not claim completion when no file changed.
- Repair turn either applies a valid fix or reports a deterministic blocked repair outcome.
- Focused tests and e2e pass.

## Evidence

Manual deep-review result on 2026-04-28:

- `bmi-empty-c-repair-transcript.txt` shows a mutation-allowed repair turn stopped after invalid `edit_file` calls for `index.html`, despite static verifier giving concrete missing items.

Additional non-technical phrasing evidence on 2026-04-28:

- `local/manual-testing/deep-review-2/nondev-bmi-title-only-transcript.txt`
  - After the user said `I'm sorry, maybe I'm saying this wrong. I need this folder to become a BMI calculator page. You can change whatever files are needed. Please make it work.`
  - Talos edited `index.html`, then repeated an edit whose `old_string` no longer matched.
  - Final result was partial:
    - duplicate `id="weight"` inputs,
    - duplicate `id="height"` inputs,
    - duplicate `id="result"` elements,
    - no calculate button,
    - no `scripts.js`,
    - no JavaScript link.
  - Trace correctly showed `FILE_EDIT mutationAllowed=true`, but repair strategy did not converge.

This strengthens the acceptance criterion: repair recovery must account for successful-but-incomplete edits as well as failed invalid edit loops. After an edit changes the anchor text, Talos should re-read before attempting another edit or switch to `write_file` for the target file.

## Current Code Read

Read before implementation:

- `work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md`
- `work-cycle-docs/tickets/done/[T22-done-high] talos-mutation-contract-overwrite-repair-phrasing.md`
- `work-cycle-docs/tickets/done/[T24-done-high] talos-blocked-tool-json-leak-after-read-only-denial.md`
- `work-cycle-docs/tickets/done/[T27-done-high] talos-malformed-toolcall-json-like-output-must-not-leak-or-stall.md`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/TaskVerificationStatus.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`

Initial diagnosis:

- T14/T22 already keep repair follow-ups mutation-capable and expose mutating tools.
- `ExecutionOutcome` already renders previous static verification failures as structured user-visible text.
- `ToolCallRepromptStage` already handles stale and empty edit repair inside one tool loop, but the repair prompt is not seeded with prior static verifier findings.
- T23 should add a small deterministic repair-context retry/instruction path rather than a broad planner.

Planned tests:

- Focused `TaskContractResolverTest` / `UnifiedAssistantModeTest` coverage for static-verification repair follow-up mutation capability and tool surface.
- Focused `AssistantTurnExecutorTest` coverage proving repair retry context includes previous static verifier findings and write-file guidance.
- Deterministic e2e scenario covering repair after prior static verification failure.

## Implementation Summary

Implemented a bounded static-verification repair-context slice:

- Added `StaticVerificationRepairContext`, a narrow helper that extracts the latest prior static verification failure from conversation history and renders a repair checklist.
- Injected the repair context into the turn messages before LLM execution for mutation-capable repair follow-ups.
- Updated `UnifiedAssistantMode` to include the same repair context in `LastPromptCapture`, keeping prompt visibility aligned with executor behavior.
- Extended repair follow-up contract inheritance so phrases like `Fix the remaining static verification problems now` inherit the prior mutation task and expected targets.
- Preserved the prior mutation request as the verification basis for inherited repair contracts, so static web verification runs on repair turns instead of downgrading to readback-only.
- Added deterministic unit and e2e coverage for verifier-context repair.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not update `CHANGELOG.md`.

## Tests Run

Red tests observed before implementation:

- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` - FAILED as expected on missing expected-target inheritance.
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon` - FAILED as expected on missing repair context in prompt capture.
- `./gradlew.bat test --tests "*staticVerificationRepairRetryPromptIncludesVerifierFindings" --no-daemon` - FAILED as expected on missing repair instruction.

Focused green tests:

- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon` - PASS.
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon` - PASS.
- `./gradlew.bat test --tests "*staticVerificationRepairRetryPromptIncludesVerifierFindings" --no-daemon` - PASS.
- `./gradlew.bat test --tests "*AssistantTurnExecutorTest" --no-daemon` - PASS.
- `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon` - PASS.

Focused e2e:

- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext" --no-daemon` - FAILED once because inherited repair contracts preserved targets but not the original web-task request, causing readback-only verification. Fixed by preserving the previous mutation request as the inherited repair contract's verification basis.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext" --no-daemon` - PASS.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.overwriteRepairPhrasingAllowsMutation" --tests "dev.talos.harness.JsonScenarioPackTest.malformedToolcallJsonLikeOutputDoesNotLeakOrMutate" --tests "dev.talos.harness.JsonScenarioPackTest.blockedReadonlyToolJsonDoesNotLeak" --tests "dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext" --no-daemon` - PASS.

Broad gates:

- `./gradlew.bat e2eTest --no-daemon` - PASS.
- `./gradlew.bat check --no-daemon` - PASS.

Note: one attempted parallel Gradle focused-test run failed with a Windows test-results file-lock cleanup error. The affected focused test was rerun sequentially and passed.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Workspace:

`local/manual-workspaces/T23/`

Model:

`qwen2.5-coder:14b`

Prompts:

```text
/session clear
/debug trace
No no I want a functioning 3-file BMI calculator. Update index.html and styles.css and create scripts.js. Make it modern and responsive. Use file tools; do not just show code.
a
Fix the remaining static verification problems now. If edit_file is fragile, overwrite index.html, styles.css, and scripts.js with complete corrected versions.
/q
```

Approval choice:

`a` for the first write prompt.

Observed tools:

- First mutation turn: `talos.read_file`, `talos.edit_file`; partial success, static verification failed and listed remaining problems.
- Repair follow-up: `talos.write_file` for `index.html`, `styles.css`, and `scripts.js`.

Files changed:

- `index.html`
- `styles.css`
- `scripts.js`

Output file:

`local/manual-testing/T23-output.txt`

Pass/fail:

PASS for T23 acceptance. The repair follow-up remained mutation-capable, exposed write tools, switched to full-file `write_file`, avoided another invalid edit loop, and reran static verification.

Notes:

The live model's repair still produced a statically incomplete app because it wrote mismatched HTML/JS/CSS IDs. Talos did not overclaim; it reported the exact remaining static problems:

- HTML did not link `scripts.js`.
- CSS referenced missing `#result`.
- JavaScript referenced missing `#bmi-form`, `#height`, `#result`, and `#weight`.

This is not a T23 blocker because T23's bounded repair requirement allows truthful incomplete outcomes after a repair attempt. It remains a product follow-up for stronger web-task repair convergence.

## Known Follow-Ups

- Live `qwen2.5-coder:14b` can still produce a full-file rewrite whose HTML, CSS, and JS disagree. The static verifier catches this, but a future repair-controller ticket should consider feeding the second verifier failure back as a bounded next repair step without creating an unbounded loop.

## Commit

Commit message:

`T23: use verifier context for bounded repair retries`

Commit hash:

Recorded in the final handoff from `git log` after commit creation. The exact
self-referential hash is not embedded here because amending this file changes
the commit hash.
