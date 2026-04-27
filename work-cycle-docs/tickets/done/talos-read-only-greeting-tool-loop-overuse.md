# [done] Ticket: Read-Only Greeting Tool-Loop Overuse
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-native-tool-surface-contract-alignment.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/done/talos-current-turn-debug-trace.md`

## Why This Ticket Exists

Installed Talos verification for the native-tool-surface ticket showed that a
simple read-only greeting no longer received mutating native tools, but the
model still used read-only tools repeatedly until the 10-iteration cap.

That means the safety leak was closed, but the turn still failed as an
interaction.

## Problem

Manual transcript on 2026-04-26:

```text
talos [auto] > hello
...
[Used 10 tool(s): talos.retrieve, talos.list_dir, talos.read_file, talos.grep | 10 iteration(s)]
[iteration limit reached]
[Tool-call limit reached. Some tool calls were not executed.]
```

No mutating tools were exposed or attempted, which is good. But Talos did not
answer a trivial greeting and burned the whole tool-loop budget.

Likely causes to inspect:

- `TaskContractResolver` correctly classifies `hello` as `READ_ONLY_QA`, but
  there is no separate "small talk / no workspace intent" contract.
- The unified prompt says to use tools for project/workspace questions, but the
  model may still over-apply workspace-tool behavior to generic greetings.
- `ToolCallLoop` has no "read-only no-progress" stop condition for repeated
  inspection after enough evidence has been gathered.
- `FailurePolicy` may need a narrow read-only downgrade: after repeated
  read-only calls on a non-workspace prompt, stop and answer from available
  context.

## Goal

Make trivial non-workspace conversational turns answer directly instead of
entering a repeated read-only tool loop.

## Scope

### In scope

- Add a deterministic task-contract or prompt-policy distinction for greetings
  / small talk / no workspace intent.
- Add a loop-level read-only no-progress stop if the model keeps inspecting
  after enough evidence or on a non-workspace prompt.
- Add tests for `hello`, `hey`, and similar turns.

### Out of scope

- Weakening read-only safety.
- Disabling tools for real workspace questions.
- Changing approval behavior.

## Proposed Work

1. Inspect `TaskContractResolver`, `UnifiedAssistantMode`, and
   `ToolCallRepromptStage` for where generic read-only turns are currently
   handled.
2. Decide whether the first slice belongs in task classification, prompt
   shaping, or failure policy.
3. Add deterministic tests:

   ```text
   hello -> no mutating tools, no repeated inspection loop, concise answer
   what is in this workspace -> still uses workspace tools
   ```

4. If the model still loops after one or two read-only calls on a non-workspace
   prompt, stop and synthesize a response rather than waiting for iteration cap.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest"
```

Installed verification:

```text
/debug on
hello
```

Expected:

- no write/edit tools exposed or called
- no 10-iteration tool loop
- a concise greeting or offer to help

## Acceptance Criteria

- Generic greetings do not burn the full tool-loop budget.
- Workspace questions still inspect the workspace.
- Safety guards for mutating tools remain unchanged.
