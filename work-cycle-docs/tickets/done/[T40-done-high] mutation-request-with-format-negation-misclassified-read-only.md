# [T40-done-high] Ticket: Mutation Request With Format Negation Misclassified Read-Only
Date: 2026-04-29
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T22 natural mutation phrasing
- T35 declarative permissions

## Context

T37 manual verification exposed a non-blocking intent-classification bug.

Prompt:

```text
Use talos.write_file to overwrite index.html. Set the content argument to the exact five letters AFTER. Do not use angle brackets. Do not use placeholders. The entire file should be AFTER.
```

Observed:

- `TaskContract` resolved to `READ_ONLY_QA`.
- `mutationAllowed=false`.
- The model emitted `talos.write_file`.
- Talos correctly blocked the tool call as read-only.
- No file changed.

The runtime safety behavior was correct for the resolved contract, but the
contract was wrong. The user's "do not use angle brackets/placeholders" wording
is a formatting constraint, not a global no-mutation request.

## Goal

Clear mutation requests must remain mutation-capable when the user includes
formatting or content constraints written as negations.

## Non-Goals

- Do not weaken global no-mutation prompts such as "do not change files".
- Do not expose mutating tools for privacy-negated small talk.
- Do not use an LLM classifier.

## Implementation Notes

The fix likely belongs in:

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`

Global no-mutation detection should distinguish:

- true mutation blockers: "do not edit files", "do not change anything"
- scoped/format constraints: "do not use placeholders", "do not use angle brackets"

## Acceptance Criteria

- "Use write_file to overwrite index.html. Do not use placeholders." resolves
  mutation-capable.
- "Overwrite index.html. Do not use angle brackets." resolves mutation-capable.
- "Do not edit files. Explain what you would change." remains read-only.
- "I am only chatting, please don't inspect my files" remains no-tool small talk.
- Mutating tools are exposed only for the mutation-capable cases.

## Tests / Evidence

Add focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
```

Run:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual installed Talos check should verify the exact prompt above stays
mutation-capable and asks approval before writing.

## Work-Test Cycle Notes

This is runtime-sensitive. Use focused tests first, then full e2e/check and
manual installed Talos verification.

## Known Risks

- Overcorrecting could weaken true no-mutation requests.
- Formatting negations and privacy negations must remain separate.

## Current Code Read

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java`

## Planned Tests

- `./gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon`
- `./gradlew.bat e2eTest --no-daemon`
- `./gradlew.bat check --no-daemon`

## Implementation Summary

- Added a narrow mutation-intent pattern for explicit `use write_file/edit_file to <mutation verb>` phrasing.
- Preserved global no-mutation handling for prompts such as `do not edit files`.
- Preserved T25 privacy/no-workspace handling for chat-only prompts.
- Added coverage at mutation-intent, task-contract, and unified-mode tool-surface layers.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not update `CHANGELOG.md`.

## Tests Run

Initial red test:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
```

Result: FAIL as expected before implementation. New tests failed in `MutationIntentTest`,
`TaskContractResolverTest`, and `UnifiedAssistantModeTest`.

Focused tests after implementation:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --no-daemon
```

Result: PASS.

E2E:

```powershell
./gradlew.bat e2eTest --no-daemon
```

Result: PASS.

Hard gate:

```powershell
./gradlew.bat check --no-daemon
```

Result: PASS.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Workspace: `local/manual-workspaces/T40/`

Model: `qwen2.5-coder:14b`

Prompt:

```text
Use talos.write_file to overwrite index.html. Set the content argument to the exact five letters AFTER. Do not use angle brackets. Do not use placeholders. The entire file should be AFTER.
```

Approval choice: `y`

Observed tools: `talos.write_file`

Files changed: `index.html` changed from `BEFORE` to `AFTER`.

Output file: `local/manual-testing/T40-output.txt`

Pass/fail: PASS.

Notes:

- Trace showed `contract: FILE_EDIT mutationAllowed=true verificationRequired=true`.
- Native/prompt tool surfaces included `talos.write_file` and `talos.edit_file`.
- A real approval prompt appeared before mutation.
- No task-contract read-only denial occurred.

## Known Follow-Ups

- None for this ticket.
