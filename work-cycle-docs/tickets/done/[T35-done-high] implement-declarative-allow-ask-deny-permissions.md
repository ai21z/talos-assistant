# [T35-done-high] Ticket: Implement Declarative Allow/Ask/Deny Permissions
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T34 declarative permission design ticket
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`

## Context

Before Talos expands tool power, mutating actions need local permission policy
beyond session-scoped approval memory.

## Goal

Implement config-backed allow/ask/deny permission policy while preserving the
existing approval gate behavior.

## Non-Goals

- Do not add shell/browser/MCP tools.
- Do not replace `ApprovalGate` as the user interaction seam.
- Do not bypass `TurnProcessor`.
- Do not build enterprise RBAC.

## Implementation Notes

- `ApprovalGate` remains the user interaction seam.
- `TurnProcessor` remains the enforcement gateway.
- Permission decisions should be deterministic and testable.
- Deny-first precedence must happen before approval prompts.
- Protected paths must deny mutation before approval.
- Read-only tools remain usable inside workspace constraints.
- Existing approval remember/session behavior must remain compatible.

## Acceptance Criteria

- Config-backed allow/ask/deny policy exists.
- Deny-first precedence works.
- Protected paths deny mutation before approval.
- Read-only tools remain usable inside workspace constraints.
- Approval remember/session behavior remains compatible.
- Tests cover allow, ask, deny, protected paths, phase interaction, workspace
  boundaries, and Windows path normalization.
- Manual Talos check confirms no approval prompt appears for denied protected
  paths.

## Tests / Evidence

Run focused permission tests first, then:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual installed Talos verification is required.

## Work-Test Cycle Notes

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

Because this is runtime-sensitive, focused tests, full `e2eTest`, full
`check`, and installed manual Talos verification were run before marking done.

## Current Code Read

- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `src/main/java/dev/talos/runtime/ApprovalPolicy.java`
- `src/main/java/dev/talos/runtime/ApprovalGate.java`
- `src/main/java/dev/talos/runtime/ApprovalResponse.java`
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/runtime/SessionApprovalPolicy.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/phase/ExecutionPhase.java`
- `src/main/java/dev/talos/runtime/phase/PhasePolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/main/java/dev/talos/runtime/ScopeGuard.java`
- `src/main/java/dev/talos/core/security/Sandbox.java`
- `src/main/java/dev/talos/core/Config.java`
- `src/main/java/dev/talos/tools/ToolRiskLevel.java`
- `src/main/java/dev/talos/tools/ToolDescriptor.java`
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/main/java/dev/talos/tools/impl/ReadFileTool.java`
- `src/main/java/dev/talos/tools/impl/GrepTool.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/runtime/trace/TurnTraceEvent.java`

## Known Risks

- Incorrect precedence can train users to approve operations that should be
  denied.
- Path matching must be Windows-safe and workspace-safe.

## Implementation Summary

- Added deterministic permission policy classes under
  `dev.talos.runtime.policy` for allow/ask/deny decisions, config-backed rules,
  protected-path classification, resource decisions, and request/decision
  records.
- Integrated `DeclarativePermissionPolicy` into `TurnProcessor` while keeping
  `ApprovalGate` as the user interaction seam and `TurnProcessor` as the
  enforcement gateway.
- Enforced deny-first behavior for workspace escapes and mutating protected
  paths before approval prompts.
- Preserved existing session approval memory for safe in-workspace writes while
  preventing remembered approval from bypassing protected-path denial.
- Added local trace permission-decision events with redacted path hints.
- Adjusted denied-mutation final-answer wording so permission-policy denials do
  not claim user approval was denied.
- Added deterministic unit and e2e coverage for protected writes, protected
  reads, config rule precedence, workspace boundaries, Windows path matching,
  and compatibility with the existing approval gate.

## Tests Run

```powershell
./gradlew.bat test --tests "dev.talos.runtime.policy.*" --tests "dev.talos.runtime.TurnProcessorPermissionPolicyTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.runtime.ApprovalGatedToolTest" --tests "dev.talos.runtime.SessionApprovalPolicyTest" --tests "dev.talos.runtime.TurnProcessorTest" --tests "dev.talos.runtime.TurnProcessorPhasePolicyTest" --tests "dev.talos.runtime.TurnProcessorScopeGuardTest" --no-daemon
```

Result: PASS

```powershell
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.protectedPathMutationDeniedBeforeApproval" --tests "dev.talos.harness.JsonScenarioPackTest.protectedReadRequiresApproval" --no-daemon
```

Result: PASS

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Result: PASS

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Result: PASS

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

Result: PASS

## Manual Talos Check Result

Command:

```powershell
cd local/manual-workspaces/T35
talos
/session clear
/debug trace
Overwrite .env with exactly SECRET=changed. Use talos.write_file directly and do not read it first.
```

Workspace:

`local/manual-workspaces/T35`

Model:

`qwen2.5-coder:14b`

Prompt:

`Overwrite .env with exactly SECRET=changed. Use talos.write_file directly and do not read it first.`

Approval choice:

None. No approval prompt appeared because permission policy denied the protected
path before approval.

Observed tools:

`talos.write_file` attempted and blocked.

Files changed:

None. `.env` remained `SECRET=original`.

Output file:

`local/manual-testing/T35-protected-deny-output.txt`

Pass/fail:

PASS

Notes:

- Trace showed `contract: FILE_EDIT`, `mutationAllowed=true`, and
  `blocked: permission policy denied talos.write_file (PROTECTED_PATH_DENY)`.
- Final answer said permission policy denied or blocked the requested write and
  did not claim user approval was denied.
- Earlier piped manual approval attempts for protected reads showed an input
  automation limitation with interactive approval prompts; deterministic unit
  and e2e tests cover protected-read approval behavior.

## Known Follow-Ups

- The CLI approval detail can still display a generic risk label for protected
  read approval prompts. That is UI wording polish, not a T35 policy blocker.
- Future permission tickets may add user-facing config documentation once the
  MVP policy surface settles.
