# [done] Ticket: Confirm Workspace State Requires Evidence
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`

## Why This Ticket Exists

"Can you confirm that?" is a normal user phrase. In a workspace context it
means inspect evidence, not give an abstract disclaimer.

## Problem

Manual transcript from installed Talos 0.9.3:

```text
It looks like it is a none completed web page right? Can you confirm that?

As an AI language model, I can't browse your local files or folders directly.
However, based on the information you provided...

Current Turn Trace
  contract: VERIFY_ONLY mutationAllowed=false verificationRequired=true
  phase: initial=INSPECT final=INSPECT
  nativeTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
  promptTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
```

The contract noticed the word `confirm`, but the turn used zero tools and still
returned a workspace claim. `verificationRequired=true` did not translate into
read-only evidence gathering.

Technical analysis:

- `TaskContractResolver.classify()` checks `verify` / `confirm` before
  workspace and diagnostic markers.
- `VERIFY_ONLY` is currently treated like a contract flag, not as an enforced
  read-only evidence plan.
- `ExecutionOutcome.fromNoTool()` can mark a no-tool `VERIFY_ONLY` answer as
  complete/read-only answered unless another truth warning fires.

## Goal

Workspace confirmation prompts should inspect relevant files or explicitly
state that confirmation could not be performed because no evidence was read.

## Scope

### In scope

- Clarify the semantics of `VERIFY_ONLY` for read-only workspace turns.
- Add no-tool enforcement for verification-required read-only tasks.
- Add tests for "confirm incomplete webpage" and similar natural phrasing.

### Out of scope

- Browser rendering or visual web validation.
- Full semantic proof of website completeness.
- Mutation verification after file writes, except where existing verifier code
  is reused.

## Proposed Work

1. Adjust task-contract resolution so `confirm` in a workspace context is not a
   generic no-evidence verify turn.
2. Add a read-only verification gate:

   - list/read obvious files for tiny workspaces
   - use static web diagnostics where applicable
   - do not accept no-tool disclaimers as completion

3. Add a deterministic scenario:

   ```text
   It looks like this is an incomplete web page, right? Can you confirm that?
   ```

4. Ensure the final answer distinguishes observed facts from inference.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI manual check:

```text
/debug trace
It looks like it is a non-completed web page, right? Can you confirm that?
/prompt last
/last trace
```

## Acceptance Criteria

- Confirmation prompts about the current workspace use read-only evidence.
- `VERIFY_ONLY` no-tool answers are blocked, retried, or visibly downgraded.
- Final wording is evidence-based and does not claim direct browser validation.
- The behavior is covered by deterministic tests.

## Resolution Notes

Implemented a read-only evidence retry in `AssistantTurnExecutor` for
verification-required workspace turns. `VERIFY_ONLY` no-tool answers are now
buffered and retried with read-only tools before a final answer is accepted.
Web completion/confirmation prompts also route through static web diagnostics,
so false "complete" claims are corrected from HTML/CSS/JS linkage facts.

Coverage:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

New scenarios:

- `src/e2eTest/resources/scenarios/40-verify-confirm-no-tool-retry.json`
- `src/e2eTest/resources/scenarios/44-verify-web-complete-static-diagnostics.json`
