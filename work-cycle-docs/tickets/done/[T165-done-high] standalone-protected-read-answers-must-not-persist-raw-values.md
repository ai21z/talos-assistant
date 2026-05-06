# T165 - Standalone Protected Read Answers Must Not Persist Raw Values

Status: done

Severity: high

## Problem

T163 redacted protected values when the assistant response contained a secret-like assignment and repeated that value elsewhere in the same answer. The next focused audit found a separate leak shape: the model can answer an approved protected read with only the value, for example:

```text
The value is: `must-not-leak`.
```

There is no `KEY=value` assignment for the existing redactor to learn from, so the raw value can persist into future session history, JSONL turn logs, and saved prompt-debug provider bodies.

## Evidence

Focused managed llama.cpp audit before this fix:

- `local/manual-testing/t157-t161-focused-response-audit-20260506-100134/`

Observed leak:

- GPT-OSS approved `.env` read answered with the standalone value.
- Raw `must-not-leak` appeared in saved session and prompt-debug artifacts.

## Scope

- Detect protected-read user requests at persistence/debug-save boundaries.
- If the assistant answer has no secret-like assignment but is answering a protected read, persist a protected-read placeholder instead of the raw answer.
- Preserve immediate approved transcript behavior.
- Preserve key-preserving redaction for assignment-style answers such as `TALOS_T61E_LLAMA_CPP_SECRET=[redacted]`.
- Apply to conversation history, JSON turn logs, prompt-debug rendered messages, and prompt-debug provider-body JSON.

## Implementation

- Added protected-read request detection and protected-read answer persistence redaction in `TraceRedactor`.
- Routed `MemoryUpdateListener` and `JsonTurnLogAppender` through the shared persistence redaction path.
- Added prompt-debug sequential message redaction so an assistant answer after a protected-read user request is redacted when it has no assignment for value-based redaction.

## Verification

Tests:

```powershell
.\gradlew.bat --no-daemon test --tests "dev.talos.runtime.MemoryUpdateListenerTest.standaloneProtectedValueAnswerIsRedactedBeforeHistoryPersistence" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest.saveRedactsStandaloneProtectedAssistantAnswerInProviderBody" --tests "dev.talos.runtime.JsonTurnLogAppenderTest.writesStandaloneProtectedAnswerAsRedactedTurnRecord"
.\gradlew.bat --no-daemon test --tests "dev.talos.runtime.MemoryUpdateListenerTest" --tests "dev.talos.runtime.JsonTurnLogAppenderTest" --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest*readOnlyReadmeProposal*"
.\gradlew.bat --no-daemon check installDist
```

Focused two-model audit:

- `local/manual-testing/t157-t161-focused-response-audit-20260506-102026/FINDINGS-T157-T161-T165-FOCUSED-RESPONSE-AUDIT.md`

Durable artifact scan result:

- `must-not-leak`: `0` matches across saved prompt-debug/session artifacts.

## Non-Goals

- Do not prevent the immediate approved answer from showing protected content to the user.
- Do not change protected-read approval policy.
- Do not create a general secret vault.
