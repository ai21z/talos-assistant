# [T44-done-medium] Ticket: Improve Live BMI Repair After Bounded Repair v1
Date: 2026-04-29
Priority: medium
Status: done
Architecture references:
- `docs/architecture/06-bounded-repair-controller.md`
- `work-cycle-docs/tickets/done/[T39-done-high] implement-bounded-repair-controller-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual testing showed bounded repair v1 is truthful and traceable, but live
qwen still failed to complete a simple broken BMI repair. Talos planned repair,
included verifier findings, required approval, created checkpoints, and did not
overclaim completion. The remaining issue is repair competence.

## Problem

After static verification failure, the model still preferred narrow `edit_file`
changes and did not apply the verifier findings to repair `scripts.js`, missing
script links, form inputs, or duplicate IDs. The second repair turn made another
partial edit and verification still failed.

## Goal

Improve bounded repair so small web files are more likely to be repaired with
complete `write_file` replacements when verifier findings show broad structural
gaps or repeated brittle edits.

## Scope

In scope:
- Repair policy prompt/plan refinement.
- Stronger write-file preference for small HTML/CSS/JS files after static web
  verification failure.
- Tests proving verifier findings lead to bounded full-file repair guidance.

Out of scope:
- Browser execution.
- Shell execution.
- Unbounded autonomous retry loops.
- LLM classifier for repair decisions.

## Proposed Work

- Review `RepairPolicy` and `StaticVerificationRepairContext` prompts.
- Add deterministic conditions for small web repair to prefer full-file writes.
- Consider a stronger stop/downgrade when the model performs another narrow
  edit that does not address verifier findings.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/repair/RepairPolicyTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for small web static failure producing full-write repair guidance.
- E2E scenario with failed verifier findings and repair follow-up.
- Manual installed Talos BMI repair prompt with qwen.

## Acceptance Criteria

- Repair plan still remains bounded.
- Verifier findings are preserved in repair context.
- Small web repair prompts strongly prefer `write_file` for complete corrected
  HTML/CSS/JS files.
- Final answer remains truthful if repair still fails.
- No read-only/privacy/status boundary regressions.

## Current Code Read

- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/verification/StaticVerificationRepairContext.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/repair/RepairPolicyTest.java`
- `src/test/java/dev/talos/runtime/toolcall/ToolCallRepromptStageTest.java`
- `src/e2eTest/resources/scenarios/62-repair-after-static-verification-failure-uses-verifier-context.json`

## Planned Tests

- Add `RepairPolicyTest` coverage that broad structural web failures produce
  full-file replacement steps for expected small web targets and use stronger
  `write_file` wording.
- Add focused tool-loop/e2e coverage if repair guidance enforcement changes.
- Run full `test`, `e2eTest`, and `check`, then run installed Talos manual BMI
  repair prompts with `qwen2.5-coder:14b`.

## Implementation Summary

- Strengthened static verification repair plans for structural small web
  failures from weak `write_file` preference to complete full-file replacement
  targets.
- Inferred conventional `index.html`, `styles.css`, and `scripts.js` targets
  for structural 3-file web repair follow-ups when the current retry prompt
  omits filenames.
- Rejected `edit_file` for full-rewrite structural web repair targets before
  approval, nudging the model to use complete `write_file` replacements.
- Prevented recovered full-rewrite repair redirects from being reported as
  partial mutation when a later `write_file` succeeds for the same target.
- Continued bounded repair prompting after a successful planned write when
  static repair full-write targets remain.
- Added deterministic scenarios for edit-to-write redirection and continuing
  until planned write targets are handled.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

- `./gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest.structuralWebFailuresRequireCompleteWritesForExpectedSmallWebTargets" --no-daemon` - RED, then PASS
- `./gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest.structuralWebRepairInfersConventionalThreeFileTargetsWhenCurrentPromptOmitsNames" --no-daemon` - RED, then PASS
- `./gradlew.bat test --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.staticVerificationRepairFollowUpCarriesVerifierProblemsIntoPrompt" --no-daemon` - PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile" --no-daemon` - PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets" --no-daemon` - RED, then PASS
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.repairAfterStaticVerificationFailureUsesVerifierContext" --no-daemon` - PASS
- `./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopP0Test*" --no-daemon` - PASS
- `./gradlew.bat test --no-daemon` - PASS after one isolated transient rerun
- `./gradlew.bat e2eTest --no-daemon` - PASS
- `./gradlew.bat check --no-daemon` - PASS

Note: one parallel focused e2e run collided on Gradle's shared
`build/test-results/e2eTest/binary` output. The affected scenario was rerun
sequentially and passed. One full `test` run reported the existing P0 partial
success assertion with an inconsistent mutation count; the focused P0 suite and
a full rerun both passed.

## Manual Talos Check Result

Command: installed Talos from fresh `clean installDist` build
Workspace: `local/manual-workspaces/T44/`
Model: `qwen2.5-coder:14b`
Prompt:
`This BMI page is broken. Fix it so it works as a 3-file webpage. Use the local files and apply the changes. If edit_file is fragile, overwrite the small files with complete corrected versions.`

Second prompt after static verification failure:
`Fix the remaining static verification problems now. If edit_file is fragile, overwrite the small files with complete corrected versions.`

Approval choice: approved with `a`
Observed tools:
- First turn: `list_dir`, `read_file`, `edit_file`; static verification failed truthfully.
- Repair turn: `write_file` for `index.html`, `styles.css`, and `scripts.js`;
  repair trace recorded `Repair: PLANNED`.
Files changed: `index.html`, `styles.css`, `scripts.js`
Output file: `local/manual-testing/T44-output.txt`
Pass/fail: PASS_WITH_FOLLOWUP
Notes: T44 improved the live behavior from brittle narrow edits to complete
file rewrites for all three small web targets. The model still produced
cross-file linkage/ID mistakes, so static verification failed and Talos did not
overclaim completion. Follow-up ticket T47 tracks cross-file coherence after
full-file repair.

## Known Follow-Ups

- `[T47-open-medium] improve-cross-file-web-repair-coherence-after-full-write.md`
  tracks the remaining live qwen BMI issue: after complete rewrites, the files
  can still disagree on script links and DOM IDs.
