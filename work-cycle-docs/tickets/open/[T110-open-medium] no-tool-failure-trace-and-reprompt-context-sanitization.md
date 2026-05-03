# T110 - No-Tool Failure Trace And Reprompt Context Sanitization

Status: Open
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

- Trace output no longer says `Status tag: ok` for blocked obligation failures.
- Session data carries a machine-readable blocked/failure outcome.
- Provider-body context for reprompts does not include unsupported model prose as
  authoritative assistant history.
- Tests cover mutation no-tool and evidence no-tool cases.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*ToolCall*" --tests "*AssistantTurnExecutor*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```
