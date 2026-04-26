# [open] Ticket: Small-Talk Capability Answer Should Describe Talos
Date: 2026-04-26
Priority: medium
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/tickets/talos-small-talk-identity-self-identification-regression.md`

## Why This Ticket Exists

Installed Talos 0.9.3 now answers direct identity prompts as Talos, but a
normal onboarding follow-up still falls back to generic base-model boilerplate.
This is one of the first things a non-developer user will ask.

## Problem

Manual transcript from `local/playground/test2`:

```text
Nice what can you do for me? How can you assist me?

As an AI language model, I can assist you with a wide range of tasks such as
answering questions, providing explanations on various topics, generating
creative content like stories or poems, offering suggestions and
recommendations, and much more...

Current Turn Trace
  contract: SMALL_TALK mutationAllowed=false verificationRequired=false
  nativeTools: none
  promptTools: none
```

The trace is reasonable for a no-tool small-talk turn, but the content is wrong
for Talos as a product. The user asked what Talos can do for them in this CLI,
not what a generic chat model can do.

Technical analysis:

- `TaskContractResolver` includes `"what can you do"` in
  `ASSISTANT_IDENTITY_MARKERS`, so the contract becomes `SMALL_TALK`.
- `AssistantTurnExecutor` deterministic identity handling only covers
  `ASSISTANT_IDENTITY_TURN_MARKERS`, which does not include
  `"what can you do"`.
- The turn therefore goes to the model with no tools and no deterministic
  product-capability answer.

## Goal

Capability/onboarding small talk should explain Talos concretely:

- local workspace inspection
- file reading/searching/retrieval
- approval-gated writes
- local model / local-first posture
- current limitations without overpromising

It should not identify as a generic "AI language model" or advertise broad
creative/chat capabilities as the main product surface.

## Scope

### In scope

- Add a deterministic or strongly guarded response for capability prompts.
- Keep pure capability prompts no-tool.
- Add tests for natural onboarding wording.
- Ensure the answer remains concise and user-friendly.

### Out of scope

- Changing `/help` command content.
- Hiding the configured model.
- Adding new tools or modes.

## Proposed Work

1. Define the supported capability prompt set, starting with:

   ```text
   what can you do
   how can you assist me
   how can you help me
   what can talos do
   ```

2. Either:

   - extend deterministic direct answers in `AssistantTurnExecutor`, or
   - add a product-capability guard in the small-talk prompt path.

3. Keep the response honest about current limitations:

   - no browser/shell tool execution in the current tool surface
   - writes require approval
   - unsupported binary documents cannot be inspected with text tools

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

Installed CLI manual check:

```text
/debug trace
Nice what can you do for me? How can you assist me?
/prompt last
```

## Acceptance Criteria

- Talos answers capability/onboarding prompts as Talos.
- The answer does not start with or rely on "As an AI language model".
- No tools are exposed or called for pure capability small talk.
- The behavior is covered by deterministic tests and one scenario or manual QA
  prompt entry.
