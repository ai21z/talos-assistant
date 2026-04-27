# [done] Ticket: Small-Talk Identity Self-Identification Regression
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/work-test-cycle.md`

## Why This Ticket Exists

Installed Talos debug QA on 2026-04-26 showed that a small-talk identity turn
stays safely no-tool, but the assistant still identifies as the underlying
model instead of Talos.

## Problem

Prompt:

```text
hello who are you?
```

Observed:

```text
Hello! I am Qwen, an AI language model developed by Alibaba Cloud.
```

The prompt render for the same turn says:

```text
You are Talos, a local-first knowledge assistant running on the user's machine.
```

The runtime classified the turn correctly:

```text
contract: SMALL_TALK mutationAllowed=false verificationRequired=false
nativeTools: none
promptTools: none
```

So this is not a tool-policy failure. It is an identity/adherence failure in
the small-talk path.

## Goal

Talos should answer identity questions as Talos, not as the base model vendor,
while still being honest that it is powered by a local model if asked directly.

## Scope

### In scope

- Strengthen small-talk identity handling.
- Add deterministic tests for identity prompts.
- Decide whether identity prompts should bypass the LLM with a local response
  or receive a stronger task-contract instruction.

### Out of scope

- Hiding the configured model in `/status`.
- Changing provider/model reporting in debug output.

## Proposed Work

1. Add exact installed-transcript prompts to tests:

   ```text
   hello who are you?
   who are you?
   what is talos?
   what model are you using?
   ```

2. For identity-only turns, consider a deterministic local response or a
   post-generation guard that rewrites vendor self-identification into an
   honest Talos identity response.
3. Keep `promptTools: none` for identity turns.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/resources/prompts/sections/identity.txt`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI check:

```text
/debug trace
hello who are you?
/prompt last
/last trace
```

## Acceptance Criteria

- Identity turns answer as Talos.
- The answer does not claim to be Qwen, Alibaba Cloud, or any other base-model
  identity unless the user explicitly asks about the underlying model.
- No tools are exposed or called for pure small talk.
- `/prompt last` and `/last trace` make the decision reviewable.

## Resolution Notes

Implemented deterministic local identity handling for identity-only small-talk
turns. Added unit coverage for non-streaming and streaming identity prompts and
JSON scenario `37-identity-small-talk-talos.json`.

Installed CLI retest in `local/playground/horror-synth-site`:

```text
hello who are you?
I am Talos, a local-first workspace assistant that can inspect files and apply approved changes in this workspace.

Current Turn Trace
  contract: SMALL_TALK mutationAllowed=false verificationRequired=false
  nativeTools: none
  promptTools: none
```
