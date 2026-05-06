# T158 - Conditional Review-And-Fix Must Inspect Before Requiring Mutation

Status: done

Severity: medium/high

## Problem

Talos currently treats prompts like "review the BMI calculator and fix any obvious issue" as unconditional mutation requests.

That is too rigid. The natural contract is conditional:

1. inspect the current artifact;
2. if an obvious blocker exists, mutate;
3. if no blocker exists, report that no file change was needed.

The current behavior can fail a good model path where the model inspects, finds no current blocker, and correctly avoids unnecessary edits.

## Evidence

T61-F managed llama.cpp audit:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/FINDINGS-LLAMA-CPP-T61F-FULL-AUDIT.md`
- Prompt: `Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.`
- Qwen turn 20:
  - task contract: `FILE_EDIT`, `MUTATING_TOOL_REQUIRED`, classification `explicit-review-and-fix-request`.
  - Talos planned static repair from older verification history.
  - turn failed as `STATIC_REPAIR_WRONG_TOOL`.
- GPT-OSS turn 20:
  - same unconditional mutation contract.
  - model inspected files but did not mutate.
  - turn failed as `REPAIR_INSPECTION_ONLY`.

Relevant code:

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/policy/ResponseObligationVerifier.java`

## Scope

- Represent conditional review/fix separately from unconditional mutation.
- Allow read-only inspection first for conditional review/fix prompts.
- If current verification/evidence shows no obvious blocker, allow a no-change answer without triggering `MUTATING_TOOL_REQUIRED` failure.
- If a current blocker is found, require the appropriate mutation tools as today.
- Avoid attaching stale static verification repair context when a later static pass supersedes the old failure for the active artifact/targets.

## Acceptance

- Add tests where a static BMI calculator already passes, user asks "review and fix any obvious issue", model inspects files only, and Talos returns a valid no-change outcome instead of `REPAIR_INSPECTION_ONLY`.
- Add tests where a static BMI calculator has a real current blocker, user asks the same prompt, and Talos still requires mutation.
- Add tests proving a previous static failure is not used as repair context after a later static pass supersedes it for the current artifact.
- Existing explicit "fix this broken file" and "repair remaining static verifier failures" prompts still require mutation.
- Existing T120/T121 repair obligation tests still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not weaken unconditional mutation prompts.
- Do not remove the pending action-obligation gate.
- Do not make broad task-planning changes.
