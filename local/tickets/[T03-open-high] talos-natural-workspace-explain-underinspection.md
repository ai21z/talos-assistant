# [open] Ticket: Natural Workspace Explain Underinspection
Date: 2026-04-26
Priority: high
Status: open
Architecture references:
- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/work-test-cycle.md`

## Why This Ticket Exists

Manual QA must represent non-developer users. The installed debug run showed
Talos failing a natural workspace question even though the system prompt and
workspace manifest gave it enough information to act.

## Problem

Prompt:

```text
I'm not a developer. What is this folder for? Please explain the website in plain English.
```

Observed:

```text
I would need to know more about the context or content of the folder...
```

But `/prompt last` showed:

```text
Workspace: .../horror-synth-site

File structure:
  index.html
  script.js
  style.css
```

The runtime exposed read-only tools:

```text
nativeTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
```

No tools were called, and Talos asked the user for context that was already
available.

## Goal

Natural workspace-explain prompts such as "what is this folder for?" should
inspect the obvious local files and answer in plain language.

## Scope

### In scope

- Expand workspace-explain intent beyond developer phrasing.
- Prefer `WORKSPACE_EXPLAIN` over generic `READ_ONLY_QA` for "this folder",
  "this directory", "what is this", and non-developer phrasing.
- Add tests and at least one installed manual QA case.

### Out of scope

- Full document understanding for binary files.
- Browser execution or live website checking.

## Proposed Work

1. Extend `TaskContractResolver` workspace markers for natural language:

   ```text
   this folder
   this directory
   what is this folder for
   explain the website
   I'm not a developer
   ```

2. Add prompt/task-contract guidance for `WORKSPACE_EXPLAIN`:

   - inspect obvious files first
   - answer in plain language
   - avoid asking for context already visible in the workspace manifest

3. Add deterministic executor coverage where a scripted model tries to answer
   without tools and Talos either retries with inspection or returns a grounded
   static explanation.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/resources/prompts/sections/unified-rules.txt`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI check in `local/playground/horror-synth-site`:

```text
/debug trace
I'm not a developer. What is this folder for? Please explain the website in plain English.
/prompt last
/last trace
```

## Acceptance Criteria

- Talos uses `list_dir` and reads obvious primary files in small workspaces.
- The answer explains the actual site from local evidence.
- Talos does not ask the user to describe files already visible in the
  workspace.
- The behavior is covered by a deterministic scenario.

## Retest Notes

Installed CLI retest after natural intent marker expansion still reproduces the
core failure in `auto`:

```text
What is this site for?

This site allows users to interact with a local workspace through various
functions such as listing directory contents, reading files...

Trace Detail
  Contract: WORKSPACE_EXPLAIN mutationAllowed=false verificationRequired=false
  Native tools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
  Tool calls: 0
```

The same prompt in `rag` mode answered correctly from retrieval sources:

```text
This site appears to be a promotional page for a horror synthwave band...
Sources
  - script.js:1-28
  - style.css:1-28
  - index.html:1-25
```

Conclusion: classifier expansion alone is insufficient. `WORKSPACE_EXPLAIN`
needs a no-tool retry/static grounding policy similar to the mutation and web
diagnostic gates.

## Additional Retest Notes - test2 installed 0.9.3

The same failure shape reproduced in `local/playground/test2` with installed
Talos 0.9.3 and `/debug trace` enabled:

```text
Can you check this folder here and tell me what is it?

Sure, I can help with that. Please provide the path of the folder you want me
to inspect.

Current Turn Trace
  contract: WORKSPACE_EXPLAIN mutationAllowed=false verificationRequired=false
  phase: initial=INSPECT final=INSPECT
  nativeTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
  promptTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
```

Important details:

- The contract was correct: `WORKSPACE_EXPLAIN`.
- Read-only tools were exposed.
- Zero tools were called.
- The answer asked for a path even though the active workspace root was already
  known and shown in the startup banner.

Technical analysis:

- This is no longer primarily a classifier problem for the initial prompt.
- The failure sits after classification: a `WORKSPACE_EXPLAIN` no-tool answer
  can still be accepted as complete when it should require inspection or a
  truthful local fallback.
- The likely owner is the no-tool path in
  `AssistantTurnExecutor.resolveNoToolAnswer` /
  `ExecutionOutcome.fromNoTool`, plus prompt/task-contract guidance for
  workspace explain turns.
