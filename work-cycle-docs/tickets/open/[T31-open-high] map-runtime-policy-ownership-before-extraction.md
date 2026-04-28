# [T31-open-high] Ticket: Map Runtime Policy Ownership Before Extraction
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`

## Context

0.9.6 proved several trust boundaries, but policy ownership remains spread
across orchestration and runtime classes. Extracting policy without a map risks
moving complexity around instead of reducing it.

## Goal

Inventory current policy responsibilities and assign each to a future policy
class before implementation begins.

## Non-Goals

- Do not implement policy classes.
- Do not refactor runtime code.
- Do not create a giant YAML phrase dump.
- Do not replace deterministic policy with an LLM classifier.

## Implementation Notes

Create a policy ownership map under `docs/architecture/` or
`work-cycle-docs/`. Inventory at least:

- `AssistantTurnExecutor`
- `TaskContractResolver`
- `MutationIntent`
- `WebDiagnosticIntent`
- `ScopeGuard`
- `StaticTaskVerifier`
- `SystemPromptBuilder`
- `ToolCallLoop`
- `ExecutionOutcome`
- `TurnProcessor`
- `ApprovalPolicy`
- `NativeToolSpecPolicy`

Assign responsibilities to the staged target policies:

- `TaskIntentPolicy`
- `SmallTalkPrivacyPolicy`
- `ToolSurfacePolicy`
- `ResourcePolicy`
- `PermissionPolicy`
- `ProtocolSanitizationPolicy`
- `VerificationPolicy`
- `RepairPolicy`
- `OutcomePolicy`
- `TracePolicy`
- `CheckpointPolicy`

## Acceptance Criteria

- A policy ownership map exists.
- Every listed current class has its current policy responsibilities described.
- Every responsibility is assigned to a future policy class.
- The map identifies the safest first extraction.
- The map identifies behavior-preserving tests required before extraction.
- No runtime implementation is included.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

Review the map against current source paths and ticket T30.

## Work-Test Cycle Notes

Use the inner dev loop. This ticket is documentation-only.

## Known Risks

- A too-broad map can become theoretical. Keep the map tied to current classes,
  methods, and tests.
