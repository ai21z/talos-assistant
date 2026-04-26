# [done] Ticket: Current-Turn Debug Trace For TaskContract And Tool Surface
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/talos-cli-debug-trace-layering.md`
Related tickets:
- `work-cycle-docs/tickets/talos-prompt-inspector-task-contract-parity.md`
- `work-cycle-docs/tickets/talos-native-tool-surface-contract-alignment.md`

## Why This Ticket Exists

The installed-CLI investigation required manual stitching across:

- saved session JSONL
- `/prompt last`
- current workspace files
- source code
- tool-loop summaries

For a disciplined local runtime, Talos should make the current turn's contract
and tool policy inspectable without requiring source-level debugging.

## Problem

Debug output currently shows useful tool usage, but it does not clearly expose:

- resolved `TaskContract`
- initial `ExecutionPhase`
- effective text prompt tool list
- effective native tool list
- why a tool was blocked: task contract, phase policy, approval denial, invalid
  args, or failure policy
- whether the final persisted answer differs from streamed visible output

This made it harder to prove whether the BMI failure was model behavior or a
runtime contract bug.

## Goal

Add a concise debug/trace view for current-turn policy decisions so future
manual verification can prove the runtime state directly.

## Scope

### In scope

- Add debug/trace-only current-turn metadata.
- Surface `TaskContract`, phase, and effective tool surfaces.
- Keep normal mode calm.
- Prefer `/last trace` or `/prompt last` enhancement if a command already fits.

### Out of scope

- Full observability framework.
- Telemetry.
- Cloud logging.
- Printing large raw prompts in normal mode.

## Proposed Work

Possible debug output in `debug=trace`:

```text
contract: FILE_CREATE mutationAllowed=true targets=[]
phase: APPLY
nativeTools: read_file,list_dir,grep,retrieve,write_file,edit_file
promptTools: read_file,list_dir,grep,retrieve,write_file,edit_file
blocked: none
```

For blocked turns:

```text
blocked: task-contract read-only denied talos.write_file
blocked: phase INSPECT denied talos.write_file
blocked: invalid edit args before approval
```

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/cli/prompt/PromptInspector.java`
- `src/main/java/dev/talos/runtime/TurnAuditCapture.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
```

Manual verification:

- Run installed Talos with `/debug trace`.
- Use `hello`, the BMI build prompt, and a denied create prompt.
- Confirm trace output explains classification and tool policy without dumping
  noisy internals in normal mode.

## Acceptance Criteria

- Developers can see current-turn contract and effective tool surface from CLI
  debug/trace output.
- Normal output remains calm.
- The trace helps distinguish model failure from runtime policy failure.
