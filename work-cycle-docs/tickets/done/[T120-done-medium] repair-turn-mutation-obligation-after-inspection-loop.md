# T120 - Repair-Turn Mutation Obligation After Inspection Loop

Severity: medium
Status: done

## Problem

In the T119 focused llama.cpp audit, GPT-OSS handled the main expected-target tasks correctly, but the final explicit "review and fix" turn repeatedly inspected files and never issued a write/edit call.

Talos contained this safely with:

`[Action obligation failed: no file was changed in this turn.]`

That is correct failure containment, but it means repair-turn quality is still weak: an explicit mutation request can spend the turn reading and then block, instead of making the required repair or ending earlier with a typed repair-obligation breach.

## Evidence

Audit:

`local/manual-testing/t119-expected-target-scope-audit-20260504-015247/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`

Relevant trace:
- Turn 7, user request: `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
- Tools used: repeated `talos.list_dir`, `talos.read_file`, and `talos.grep`
- No `talos.write_file` or `talos.edit_file`
- Outcome: `BLOCKED_BY_POLICY`
- Action obligation: `MUTATING_TOOL_REQUIRED (FAILED) - retry response issued tool calls but no write/edit tool calls`

## Scope

- Improve explicit repair/fix turns where mutation is required but the model only inspects.
- Keep this focused on action-loop state, not broad prompt rewriting.
- Preserve safe blocking when no valid mutation is produced.
- Do not weaken protected-file handling or approval/checkpoint behavior.

## Acceptance

- Done: a scripted executor test covers a repair/fix turn where the model performs read-only tools and no mutation.
- Done: runtime records `failureKind=REPAIR_INSPECTION_ONLY` on the failed action-obligation event.
- Done: failure output is failure-dominant and contains no model-authored success prose.
- Done: the retry remains bounded to the existing missing-mutation retry path; no infinite retry loop was added.
- Done: happy paths remain unchanged when the model reads and then writes an allowed repair target.
- Done: existing T119 off-target expected-target blocks still pass.

## Verification

- `./gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.repairFixRetryWithOnlyInspectionToolsGetsTypedRepairBreach'`
- `./gradlew.bat --no-daemon test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming'`
- `./gradlew.bat --no-daemon test --tests dev.talos.runtime.ToolCallLoopTest --tests dev.talos.runtime.TurnProcessorTest`
- `./gradlew.bat --no-daemon test`
- `./gradlew.bat --no-daemon build`

## Non-Goals

- No full T61-style audit as part of this ticket.
- No broad provider abstraction.
- No new model selection policy.
- No proposal/apply redesign.
