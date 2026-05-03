# T110 - No-Tool Failure Trace And Reprompt Context Sanitization

Status: Done
Priority: Medium
Branch: v0.9.0-beta-dev
Source: T106 focused managed llama.cpp audit

## Evidence Summary

T106 showed visible containment working for no-tool mutation and evidence
failures, but trace and reprompt state still need hardening:

- Blocked no-tool mutation turns displayed:
  `[Action obligation failed: no file was changed in this turn.]`
- Evidence-required no-tool turns displayed:
  `[Evidence incomplete: required workspace evidence was not gathered in this turn.]`
- The same turns still reported `Status: ok`, `Outcome: NO_TOOL_RESPONSE`, and
  `Status tag: ok` in trace output.
- Same-turn reprompt provider-body context can include unsupported no-tool model
  prose before the runtime-owned failure block is finalized.

## Goal

Represent no-tool obligation failures as structured runtime failure state in
trace/session data and avoid feeding unsupported no-tool prose back into
reprompt context.

## Scope

- Add typed status/outcome for no-tool mutation obligation failures.
- Add typed status/outcome for no-tool evidence/inspection obligation failures.
- Replace unsupported no-tool assistant prose in same-turn reprompt context with
  a runtime-owned summary before asking for correction.
- Preserve visible failure-dominant output.
- Keep successful tool-call paths unchanged.

## Acceptance Criteria

- [x] Trace output no longer says `Status tag: ok` for blocked obligation failures.
- [x] Session data carries a machine-readable blocked/failure outcome.
- [x] Provider-body context for reprompts does not include unsupported model prose as
  authoritative assistant history.
- [x] Tests cover mutation no-tool and evidence no-tool cases.

## Implementation Notes

- `/last trace` now prefers the local trace outcome when present, so blocked
  mutation no-tool turns render `Status: BLOCKED`, `Outcome:
  BLOCKED_BY_POLICY`, and `Status tag: BLOCKED` instead of persisted
  `ok`/`NO_TOOL_RESPONSE`.
- Evidence no-tool turns with a local `ADVISORY_ONLY` outcome render that
  structured outcome instead of a generic no-tool response.
- Mutation no-tool retry coverage now asserts that unsupported no-tool model
  prose is not replayed as authoritative assistant history; the retry context
  uses Talos-owned action-obligation summary text.

## Verification Run

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
./gradlew.bat test --tests "*ToolCall*" --tests "*AssistantTurnExecutor*" --tests "*ExplainLastTurnCommand*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

## Suggested Verification

```powershell
./gradlew.bat test --tests "*ToolCall*" --tests "*AssistantTurnExecutor*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```
