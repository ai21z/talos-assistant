# [done] Ticket: Deictic Workspace Follow-Up Loses Prior Intent
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/[T03-done-high] talos-natural-workspace-explain-underinspection.md`

## Why This Ticket Exists

Users do not repeat full technical prompts every turn. They say "this here",
"that folder", "yes, check it", and expect Talos to preserve conversational
intent. The current task contract is too current-turn-only for that flow.

## Problem

Manual transcript from installed Talos 0.9.3:

```text
Can you check this folder here and tell me what is it?

Sure, I can help with that. Please provide the path of the folder you want me
to inspect.
```

Follow-up:

```text
this here

> Using list_dir: .

The folder contains two files:

1. index.html - This is an HTML file...
2. styles.css - This is a CSS file...

Current Turn Trace
  contract: READ_ONLY_QA mutationAllowed=false verificationRequired=false
```

Talos did call `list_dir`, but it did not preserve the prior request's actual
intent: explain what the folder is. It gave generic file-type descriptions and
did not read the obvious primary files.

## Goal

Short deictic follow-ups after a workspace-explain prompt should inherit the
workspace-explain intent enough to inspect the current workspace and answer the
original user goal.

## Scope

### In scope

- Handle deictic phrases such as `this here`, `this folder`, `this one`,
  `yes this`, and `here`.
- Preserve prior turn intent for read-only workspace explanation and diagnosis.
- Add deterministic tests that include a two-turn conversation.

### Out of scope

- Long-term autonomous memory.
- Multi-session intent inference.
- Mutation carry-over without explicit current-turn approval.

## Proposed Work

1. Add a small current-session follow-up resolver for deictic read-only turns.
2. Keep mutation safety strict: prior mutation intent must not authorize a new
   write on a vague follow-up.
3. When the inherited intent is `WORKSPACE_EXPLAIN`, require the same
   inspection policy as a direct workspace-explain prompt.
4. Add a scenario where the first turn asks to inspect the folder and the
   second says `this here`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/core/context/ConversationCompactor.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI manual check:

```text
/debug trace
Can you check this folder here and tell me what is it?
this here
/last trace
```

## Acceptance Criteria

- The follow-up `this here` after a workspace-explain turn resolves to an
  explain/inspect behavior, not generic read-only QA.
- Talos reads obvious primary files in a tiny web workspace before explaining
  what it is.
- Vague follow-ups do not grant write permission.
- The behavior is covered by a two-turn deterministic scenario.

## Resolution Notes

Added read-only deictic follow-up inheritance in `TaskContractResolver`.
Short prompts such as `this here`, `this folder`, and `here` can inherit the
previous read-only workspace explain/diagnose/verify contract while still
refusing to inherit mutation permission.

Coverage:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
```

The inherited `WORKSPACE_EXPLAIN` contract uses the same evidence retry policy
covered by scenario 39.
