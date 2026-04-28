# [T34-done-high] Ticket: Design Declarative Allow/Ask/Deny Permissions
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/02-runtime-policy-ownership-map.md`

## Context

Current approval behavior is session-scoped and tool-risk based. Talos needs a
declarative local permission MVP before adding more dangerous capabilities.

## Goal

Design a local allow/ask/deny permission policy with tool, path, phase, and
risk awareness.

## Non-Goals

- Do not implement permissions yet.
- Do not create enterprise RBAC.
- Do not add cloud policy services.
- Do not add shell/browser/MCP tools.

## Implementation Notes

The design must define:

- config file location or locations
- config format
- deny-first precedence
- protected path defaults
- interaction with existing `ApprovalPolicy`
- interaction with `ApprovalGate`
- interaction with `TurnProcessor`
- interaction with phase policy
- test matrix

Protected paths to consider:

- `.env`
- `.env.*`
- `**/secrets/**`
- `**/*secret*`
- `**/*token*`
- `**/*credential*`
- private keys
- SSH keys
- cloud credential files

The final protected-path list must be justified and tested.

## Acceptance Criteria

- The design uses allow/ask/deny, not RBAC.
- Deny beats ask, and ask beats allow.
- Defaults are conservative for mutating operations.
- Read-only tools may auto-allow only inside workspace constraints.
- Protected path behavior is specified.
- Interaction with existing approval/session remember behavior is specified.
- The test matrix covers allow, ask, deny, protected paths, phase interaction,
  workspace boundaries, and Windows path normalization.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

## Work-Test Cycle Notes

Design-only ticket. This should unblock T35.

## Current Code Read

- `src/main/java/dev/talos/runtime/ApprovalPolicy.java`
- `src/main/java/dev/talos/runtime/ApprovalGate.java`
- `src/main/java/dev/talos/runtime/ApprovalResponse.java`
- `src/main/java/dev/talos/runtime/NoOpApprovalGate.java`
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
- `src/test/java/dev/talos/runtime/ApprovalGatedToolTest.java`
- `src/test/java/dev/talos/runtime/SessionApprovalPolicyTest.java`
- `src/test/java/dev/talos/runtime/TurnProcessorTest.java`
- `src/test/java/dev/talos/runtime/TurnProcessorPhasePolicyTest.java`
- `src/test/java/dev/talos/runtime/TurnProcessorScopeGuardTest.java`

## Planned Evidence

```powershell
./gradlew.bat test --no-daemon
```

## Implementation Summary

Created `docs/architecture/04-declarative-allow-ask-deny-permissions.md`.
The design defines a local allow/ask/deny permission MVP around typed
permission decisions, user-owned config, deny-first precedence, protected path
defaults, `TurnProcessor` enforcement, `ApprovalGate` prompting, phase-policy
boundaries, trace requirements, and the T35 test matrix.

No runtime behavior was changed.

## Tests Run

```powershell
./gradlew.bat test --no-daemon
```

Result: PASS.

## Work-Test Cycle Loop Used

Inner dev loop only. This design ticket did not declare a versioned candidate,
did not bump the patch version, and did not update `CHANGELOG.md`.

## Known Follow-Ups

- T35 should implement the permission MVP from the design.
- Broad protected-content handling for `grep`, `retrieve`, and indexing may
  need a separate resource/indexing policy slice if it is too large for T35.

## Known Risks

- A broad permission system can become enterprise governance. Keep the MVP
  local, understandable, and user-controlled.
