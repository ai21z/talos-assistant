# [T35-open-high] Ticket: Implement Declarative Allow/Ask/Deny Permissions
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T34 declarative permission design ticket

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

Use the inner dev loop while implementing. This is runtime-sensitive, so full
`check` and manual verification are required before marking done.

## Known Risks

- Incorrect precedence can train users to approve operations that should be
  denied.
- Path matching must be Windows-safe and workspace-safe.
