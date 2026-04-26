# [done] Ticket: Block Negative Local-Access Claims On Workspace Turns
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/work-test-cycle.md`
- `local/tickets/talos-execution-outcome-centralization.md`
- `local/tickets/[T03-open-high] talos-natural-workspace-explain-underinspection.md`

## Why This Ticket Exists

Talos cannot be trusted as a local workspace assistant if it repeatedly tells
the user it cannot access local files while the runtime has exposed local file
tools for the turn.

## Problem

Manual transcript from installed Talos 0.9.3 in `local/playground/test2`:

```text
It looks like it is a none completed web page right? Can you confirm that?

As an AI language model, I can't browse your local files or folders directly...
```

Then:

```text
But you told me you can help me with that. What is the problem with this workspace?

I apologize for any confusion. As an AI language model, I don't have direct
access to your local workspace or files to analyze them...

Current Turn Trace
  contract: WORKSPACE_EXPLAIN mutationAllowed=false verificationRequired=false
  nativeTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
  promptTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
```

Then:

```text
But you can read the files in this workspace

I apologize for any confusion earlier. As an AI language model, I don't have
direct access to your local workspace or files...

Current Turn Trace
  contract: WORKSPACE_EXPLAIN mutationAllowed=false verificationRequired=false
  nativeTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
  promptTools: talos.grep, talos.list_dir, talos.read_file, talos.retrieve
```

The runtime exposed the correct read tools, but the final answers contradicted
the tool surface.

## Goal

For workspace-capable contracts, Talos must not finalize a no-tool answer that
claims it cannot access or inspect the local workspace. It should either:

- use the available read tools, or
- respond with a truthful local-assistant correction and invite/perform the
  appropriate inspection.

## Scope

### In scope

- Detect negative local-access claims on workspace/tool-capable contracts.
- Route them through the centralized outcome/no-tool path.
- Add deterministic coverage for `WORKSPACE_EXPLAIN`, `READ_ONLY_QA`, and
  `VERIFY_ONLY` variants.
- Preserve honest limitation statements for unsupported capabilities, such as
  binary document contents that text tools cannot inspect.

### Out of scope

- Pretending Talos has browser, shell, OCR, or binary document parsing tools.
- Changing approval policy for writes.
- Adding cloud tools or external network retrieval.

## Proposed Work

1. Add a negative-capability detector for phrases such as:

   ```text
   I don't have direct access to your local workspace
   I can't browse your local files
   I can't access your files
   If you provide the file contents
   ```

2. Scope the detector to turns where local read tools are available and the
   `TaskContract` is workspace-related.
3. Decide the central policy:

   - non-streaming: retry once with an explicit "use tools or correct the
     capability claim" instruction
   - streaming: visible replacement/annotation because text may already have
     reached the terminal

4. Add a deterministic e2e scenario where the scripted model emits a negative
   local-access claim despite tool availability.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest
```

Installed CLI manual check:

```text
/debug trace
But you can read the files in this workspace
/prompt last
/last trace
```

## Acceptance Criteria

- Workspace/tool-capable turns do not finalize "I cannot access local files"
  answers when read tools are available.
- The final answer is truthful about Talos's actual local tool surface.
- Unsupported capability limitations remain allowed when scoped to the actual
  missing capability.
- The finding is covered by deterministic tests.

## Resolution Notes

Implemented a centralized no-tool outcome correction for negative local
workspace/file access claims. Affected turns now become advisory and use a
truthful capability correction instead of finalizing the model's denial.

The correction is scoped to non-mutation workspace turns so it does not mask
explicit mutation safety behavior. Streaming mutation requests with no tool
execution remain tracked by
`local/tickets/talos-streaming-no-tool-explicit-mutation-and-selector-grounding.md`.

Streaming turns also emit the correction to the stream sink so interactive users
see the correction, while the stored final answer excludes the raw negative
claim.

Added deterministic coverage in:

- `ExecutionOutcomeTest`
- `JsonScenarioPackTest`
- `scenarios/38-no-tool-local-access-claim-corrected.json`
