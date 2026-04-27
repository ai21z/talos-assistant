# [done] Ticket: Native Tool Surface Must Match TaskContract
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/done/talos-read-only-turns-should-avoid-unsolicited-mutation-attempts.md`
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`

## Why This Ticket Exists

Installed verification showed that read-only prompt text can correctly tell the
model not to use mutating tools while the native Ollama tool surface still
offers `write_file` and `edit_file`.

That makes the model/tool boundary internally contradictory.

The source-of-truth architecture explicitly says prompt text is not enough for
critical invariants. Tool availability must be policy-backed, not only
described in prose.

## Problem

Actual `/prompt last` for a read-only turn showed:

```text
Current Turn Contract
- This specific user turn is read-only or diagnostic.
- Do not call talos.write_file or talos.edit_file in this turn.
```

The system prompt's visible tool list included only inspection tools.

But native tools are wired globally:

- `TalosBootstrap` calls `llm.setToolSpecs(...)` once with every registry tool.
- `LlmClient.chatStreamFull(...)` and `chatFull(...)` pass that global
  `toolSpecs` list into every `ChatRequest`.
- `OllamaChatClient` serializes `req.tools` into the request body when native
  tool calling is enabled.

So on read-only turns, the model can still select mutating native tools. The
runtime later blocks them, but the turn is already noisy and misdirected.

## Goal

Make the native tool surface match the current `TaskContract` and execution
phase for each turn.

Read-only/diagnostic turns should not expose mutating native tools to the model.
Mutation-capable turns should expose write/edit tools, still guarded by approval
and phase policy.

## Scope

### In scope

- Per-turn filtering of native `ToolSpec` objects before engine requests.
- Reuse existing `TaskContract` / `ExecutionPhase` / tool risk metadata.
- Preserve runtime guards in `TurnProcessor` as defense in depth.
- Add tests proving read-only native requests omit mutating tools.
- Update prompt inspection/debug output so it reports the actual native tool
  surface, not only the registry.

### Out of scope

- Removing approval gates.
- Removing mutation-intent or phase guards.
- Adding new tools or broad tool metadata systems beyond the narrow filter.
- MCP server implementation.

## Proposed Work

1. Introduce a per-turn tool-spec selection point.

   Preferred direction:

   ```text
   UnifiedAssistantMode / AssistantTurnExecutor
     -> resolve TaskContract
     -> select allowed ToolSpec list
     -> pass list into LlmClient request for this call
   ```

   Avoid mutating global `LlmClient.toolSpecs` around each request if possible,
   because global mutable state risks cross-turn leakage.

2. Add a small API seam if needed.

   Possible designs:

   - extend `LlmClient.chatFull/chatStreamFull` to accept an optional per-call
     `List<ToolSpec>`
   - introduce a request options object
   - create a `ToolSpecPolicy` helper that maps `TaskContract` + registry
     descriptors to allowed specs

3. Align prompt rendering with actual request behavior.

   `/prompt last` should show what the actual request used. `/prompt <input>`
   currently has a separate bug tracked in
   `talos-prompt-inspector-task-contract-parity.md`.

4. Keep the hard guard.

   `TurnProcessor` should still reject mutating calls on read-only turns even
   if the tool surface filter fails or text-fallback protocol appears.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/core/llm/LlmClient.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java`
- `src/test/java/dev/talos/runtime/NativeToolPipelineTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/prompt/` if prompt inspector tests exist or are
  added

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.NativeToolPipelineTest"
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
```

Required assertions:

- read-only `TaskContract` request sends only read/search/retrieve native specs
- mutation-capable `TaskContract` request sends write/edit specs
- text prompt and native tool surface do not disagree
- `TurnProcessor` still blocks mutating calls if one appears anyway

Installed verification:

- Run `hello` in `local/playground/horror-synth-site` with debug on.
- Confirm no `write_file` / `edit_file` native attempt appears.
- Run a clear create prompt and confirm approval is requested.

## Acceptance Criteria

- Native tool availability is phase/task-aware per turn.
- Read-only turns do not offer write/edit native tools.
- Mutation turns still allow write/edit through approval-gated execution.
- Existing runtime guards remain in place.
- The actual prompt/debug evidence can show the selected native tool surface.
