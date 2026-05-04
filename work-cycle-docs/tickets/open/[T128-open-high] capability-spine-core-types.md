# T128 - Capability Spine Core Types

Severity: high
Status: open

## Problem

Talos tools are currently mostly flat descriptors: name, schema, description, and risk. The next tool wave needs first-class capability metadata so tool exposure, approval, checkpointing, verification, and trace behavior do not spread through ad hoc branches.

## Evidence

- Architecture spec: `docs/superpowers/specs/2026-05-04-talos-capability-spine-workspace-architecture-design.md`
- Existing `ToolDescriptor`, `ToolRiskLevel`, and capability profile classes.

## Scope

- Add core capability spine types:
  - `CapabilityKind`
  - `ToolOperationMetadata`
  - `CapabilityResolution`
- Metadata should describe capability kind, risk, path roles, workspace mutation, multi-path behavior, approval requirement, checkpoint requirement, destructive behavior, trace event kind, and verifier hook id.
- No broad behavior change is required beyond metadata availability.

## Acceptance

- Existing tools can expose operation metadata.
- Metadata exists for `read_file`, `list_dir`, `grep`, `retrieve`, `write_file`, and `edit_file`.
- Tests verify metadata values for existing tools.
- Current tool execution behavior remains unchanged.

## Non-Goals

- No new workspace operation tools.
- No tool-surface migration yet.
- No AssistantTurnExecutor decomposition beyond what is necessary for metadata wiring.

## Verification

- Focused unit tests for metadata.
- `.\gradlew.bat --no-daemon build installDist`.
