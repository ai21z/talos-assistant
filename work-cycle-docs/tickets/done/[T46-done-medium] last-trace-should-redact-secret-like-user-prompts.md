# [T46-done-medium] Ticket: Last Trace Should Redact Secret-Like User Prompts
Date: 2026-04-29
Priority: medium
Status: done
Architecture references:
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `work-cycle-docs/tickets/done/[T33-done-high] implement-local-turn-trace-model-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Context

During the 0.9.7 candidate smoke, protected `.env` mutation was correctly denied
before approval and `.env` remained unchanged.

Prompt:

```text
Overwrite .env with SECRET=changed. Use talos.write_file.
```

The local trace section did not expose `.env` contents, but `/last trace`
displayed the raw user request in the human-readable `User Request` section.
That can echo secret-like values supplied by the user.

## Goal

`/last trace` should avoid displaying raw secret-like user prompt content by
default, especially when protected paths or `KEY=value`-style secret material
appears in the request.

## Non-Goals

- Do not remove user-request visibility entirely if a safe redacted summary is
  available.
- Do not change local trace full/debug opt-in behavior without an explicit
  design update.
- Do not weaken protected-path denial.

## Implementation Notes

- Review the `/last trace` rendering path and the local trace redaction policy.
- Reuse or extend existing redaction helpers instead of adding ad hoc string
  cleanup.
- Candidate redactions:
  - `SECRET=changed` -> `SECRET=[redacted]`
  - token-like values -> `[redacted]`
  - protected path payload previews -> hash/count metadata only

## Acceptance Criteria

- `/last trace` does not display raw `KEY=value` secret-like payloads from user
  prompts by default.
- Protected path mutation/read denials still show enough context to debug the
  policy decision.
- Explicit opt-in debug/full trace behavior remains clearly marked if full
  content is ever shown.
- Tests cover protected `.env` prompt rendering.

## Tests / Evidence

- Add unit coverage for `/last trace` rendering redaction.
- Add manual installed Talos check with a protected `.env` mutation denial.

## Work-Test Cycle Notes

Use the inner dev loop. This ticket is not part of the 0.9.7 candidate
closeout.

## Current Code Read

- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- `src/main/java/dev/talos/runtime/trace/TraceRedactor.java`
- `src/test/java/dev/talos/cli/repl/slash/ExplainLastTurnCommandTest.java`

## Planned Tests

- Add `/last trace` rendering coverage proving `SECRET=changed` in the user
  request is displayed as `SECRET=[redacted]`.
- Preserve useful protected-path/tool/policy metadata in the same rendered trace.

## Implementation Summary

- Reused the local trace redaction seam by adding
  `TraceRedactor.redactSecretLikeAssignments(...)`.
- Redacted secret-like `KEY=value` assignments in the human-readable
  `User Request` preview rendered by `/last`, including `/last trace`.
- Preserved useful context such as `.env`, `talos.write_file`, task/policy
  trace fields, tool failure reason, and `PROTECTED_PATH_DENY`.
- Added direct redactor coverage for `SECRET`, `TOKEN`, `API_KEY`, `PASSWORD`,
  and `CREDENTIAL`.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest.traceViewRedactsSecretLikeValuesFromUserRequestPreview" --no-daemon
```

Result: FAIL before implementation, then PASS after implementation.

```powershell
./gradlew.bat test --tests "dev.talos.runtime.trace.TraceRedactorTest" --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.slash.ExplainLastTurnCommandTest" --no-daemon
```

Result: PASS.

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Result: PASS.

Note: one early attempt to run two focused Gradle test commands in parallel hit
a Windows file-lock cleanup error under `build/test-results/test/binary`.
Both focused tests passed when rerun sequentially.

## Manual Talos Check Result

Command:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Workspace:
`local/manual-workspaces/T46/`

Model:
`qwen2.5-coder:14b`

Prompt:

```text
Overwrite .env with SECRET=changed. Use talos.write_file.
```

Approval choice:
No approval prompt appeared.

Observed tools:
`talos.write_file` attempted and blocked by permission policy.

Files changed:
None. `.env` remained `SECRET=original`.

Output file:
`local/manual-testing/T46-output.txt`

Pass/fail:
PASS.

Notes:
`/last trace` displayed `Overwrite .env with SECRET=[redacted]. Use
talos.write_file.` and retained `.env`, `talos.write_file`, and
`PROTECTED_PATH_DENY` metadata. The raw transcript did not contain
`SECRET=changed`.

## Known Follow-Ups

- T43 remains responsible for improving protected-read approval risk/outcome
  labels.
- T45 remains responsible for data minimization in simple folder listing.

## Known Risks

- Over-redaction can make traces hard to debug. Preserve path and policy reason
  metadata while redacting only sensitive values.
