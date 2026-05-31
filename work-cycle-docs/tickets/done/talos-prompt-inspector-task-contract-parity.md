# [done] Ticket: Prompt Inspector TaskContract Parity
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-prompt-inspector.md`
- `docs/architecture/talos-harness-source-of-truth.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`
- `work-cycle-docs/tickets/done/talos-native-tool-surface-contract-alignment.md`

## Why This Ticket Exists

During the incident investigation, `/prompt <input>` produced misleading
debug output. It did not match the real prompt path used by
`UnifiedAssistantMode`.

For debugging Talos, prompt inspection must be trustworthy. If prompt debug
lies about task contract, tool surface, or read-only state, it slows diagnosis
and can hide architecture bugs.

## Problem

`UnifiedAssistantMode` resolves a `TaskContract` for the current raw line and
passes `withReadOnlyToolMode(!taskContract.mutationAllowed())` to
`SystemPromptBuilder`.

`PromptInspector.renderNext(...)` builds a prompt independently and currently
does not apply the same `TaskContract` logic for the supplied input.

Result:

- `/prompt last` reflects the actual prompt sent by the last real turn.
- `/prompt <input>` can show all tools and no current-turn contract even when
  the actual turn would be read-only.
- The `Tools exposed` line reports registry tools, not necessarily the
  effective per-turn native/tool prompt surface.

## Goal

Make `/prompt <input>` and `/prompt last` accurately reflect the same
TaskContract, read-only mode, tool list, and native-tool selection that a real
turn would use.

## Scope

### In scope

- Apply `TaskContractResolver.fromUserRequest(input)` in prompt render paths.
- Show the resolved `TaskContract` explicitly in prompt debug output.
- Make `Tools exposed` distinguish registry tools from effective prompt/native
  tools if they differ.
- Add tests for prompt inspector parity.

### Out of scope

- Changing actual runtime tool policy; that is tracked separately.
- Broad prompt redesign.
- UI color/layout work.

## Proposed Work

1. Update `PromptInspector.renderNext(...)`.

   Match `UnifiedAssistantMode`:

   ```text
   resolve TaskContract from user input
   pass readOnlyToolMode to SystemPromptBuilder
   inject/represent TaskContract instruction consistently
   ```

2. Improve `PromptRender`.

   Consider adding fields:

   - `TaskContract taskContract`
   - `List<String> registryTools`
   - `List<String> effectivePromptTools`
   - `List<String> effectiveNativeTools`

   Keep this narrow if a smaller change suffices.

3. Add tests around exact incident prompts.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/prompt/PromptInspector.java`
- `src/main/java/dev/talos/cli/prompt/PromptRender.java`
- `src/main/java/dev/talos/cli/repl/slash/PromptCommand.java`
- `src/test/java/dev/talos/cli/prompt/`
- existing prompt command tests if present

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.prompt.*"
./gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptCommandTest"
```

Manual verification:

```text
/prompt hello
/prompt Can you build a small BMI calculator website here with separate CSS and JavaScript files? Use the file tools if you can; do not just show code.
/prompt last
```

Expected:

- displayed TaskContract matches real turn behavior
- tool exposure lines are not misleading
- read-only and mutation turns are clearly distinguishable

## Acceptance Criteria

- `/prompt <input>` is a reliable preview of a real next prompt.
- `/prompt last` and `/prompt <same input>` do not disagree on task contract
  except for expected history differences.
- Debug output shows effective tool surfaces clearly.
