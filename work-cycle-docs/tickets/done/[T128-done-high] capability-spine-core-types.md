# T128 - Capability Spine Core Types

Severity: high
Status: done

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

## Architecture Metadata

- Capability: capability spine.
- Operation(s): metadata declaration only.
- Owning package/class: `dev.talos.core.capability`, `dev.talos.tools`, `dev.talos.runtime.capability`.
- New or changed tools: no new tools; existing `read_file`, `list_dir`, `grep`, `retrieve`, `write_file`, and `edit_file` expose metadata.
- Risk level: unchanged; metadata mirrors existing read/write risk.
- Approval behavior: unchanged; metadata records approval requirement for later planners.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged; metadata records checkpoint expectation for mutating tools.
- Evidence obligation: unchanged; `CapabilityResolution` adds a typed field for later policy use.
- Verification profile: unchanged; metadata records verifier hook ids where applicable.
- Repair profile: unchanged.
- Outcome/truth warnings: unchanged.
- Trace/debug fields: metadata records trace event kind for each tool.
- Refactor scope: descriptor metadata wiring only.
- Non-goals: no behavior migration, no new workspace operations, no executor decomposition.

## Verification

- Focused unit tests for metadata.
- `.\gradlew.bat --no-daemon build installDist`.
