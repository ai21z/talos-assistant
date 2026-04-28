# [T34-open-high] Ticket: Design Declarative Allow/Ask/Deny Permissions
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`

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

## Known Risks

- A broad permission system can become enterprise governance. Keep the MVP
  local, understandable, and user-controlled.
