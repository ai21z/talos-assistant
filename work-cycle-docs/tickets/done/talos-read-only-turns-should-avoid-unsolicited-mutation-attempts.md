# [done] Ticket: Read-Only Turns Should Avoid Unsolicited Mutation Attempts
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-invalid-mutation-should-not-trigger-missing-mutation-retry.md`

## Why This Ticket Exists

Installed Talos manual verification showed that a read-only selector inspection
turn can still cause the model to emit `talos.edit_file` calls. The runtime
correctly blocks those calls before approval and the newer failure discipline
stops further tool execution before the iteration cap, but the attempted
mutation still appears in the tool transcript.

This is safe on disk, but it is not ideal discipline: read-only turns should
avoid mutating tool attempts instead of depending on policy rejection.

## Problem

Talos has hard runtime guards for read-only turns:

- `TaskContractResolver` classifies read-only user intent.
- `TurnProcessor.executeTool(...)` rejects mutating tools before approval when
  mutation is not allowed.
- `ToolCallRepromptStage` now stops further tool execution after mutating
  DENIED outcomes.

Those guards protect the workspace, but the model can still choose a mutating
tool in the first place. That creates noisy transcripts, wasted LLM/tool loop
steps, and user-visible summaries that include failed edit attempts during a
read-only question.

## Goal

Reduce or eliminate unsolicited mutating tool attempts during read-only turns
without weakening the existing hard policy guards.

## Scope

### In scope

- Review the current system prompt/tool instructions for read-only versus
  mutation turns.
- Consider using `TaskContract`/`ExecutionPhase` context to make mutating tools
  less attractive or unavailable in read-only phases.
- Add deterministic scenario or unit coverage if behavior can be asserted
  without depending on model sampling.

### Out of scope

- Removing the hard mutation-intent guard.
- Allowing read-only prompts to mutate files.
- Broad planner or multi-agent work.
- Adding shell/browser/MCP/cloud tool surfaces.

## Proposed Work

- Inspect how tool descriptions and system instructions are assembled for
  `AssistantTurnExecutor`/runtime tool calls.
- Identify whether read-only task contract state can be surfaced in the prompt
  or tool availability metadata before the model chooses tools.
- Keep the runtime guard as the final authority; any prompt/tool-surface change
  is only a first-line steering improvement.
- If a deterministic harness path exists, add a JSON scenario asserting that a
  read-only turn with scripted mutating attempts is blocked and summarized
  cleanly. If avoiding the attempt itself cannot be deterministic, document that
  boundary and rely on manual installed verification.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/phase/PhasePolicy.java`
- system prompt/tool instruction assembly code
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Focused tests around read-only task contract prompt/tool policy if added.
- `./gradlew.bat --no-daemon test`
- `./gradlew.bat --no-daemon e2eTest`
- Installed Talos manual horror-synth run.

## Acceptance Criteria

- Read-only turns remain protected by hard policy guards.
- Talos no longer routinely attempts `write_file`/`edit_file` during the
  standard read-only horror-synth selector inspection prompt, or the remaining
  attempt is explicitly documented as a model-behavior limitation.
- No runtime safety regression in approval, phase policy, or failure policy.

## Completion Notes

- Added current-turn read-only task-contract guidance before tool execution.
- Added read-only prompt/tool-surface mode for unified turns so read-only
  requests list only inspection tools and omit mutating tool descriptors.
- Kept hard runtime mutation guards unchanged as the authority.
- Installed Talos verification on `local/playground/horror-synth-site` showed
  the standard read-only selector-inspection prompt used `talos.list_dir`,
  `talos.read_file`, and `talos.grep` only; no `talos.write_file` or
  `talos.edit_file` attempt occurred during that turn.
- The same manual transcript still showed a separate model-quality issue on the
  later mutation prompt: the model first emitted invalid empty `edit_file`
  arguments before any approval could be requested. That is not part of this
  read-only-turn ticket.
