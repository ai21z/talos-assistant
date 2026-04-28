# [T31-done-high] Ticket: Map Runtime Policy Ownership Before Extraction
Date: 2026-04-28
Priority: high
Status: done
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/02-runtime-policy-ownership-map.md`

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

## Current Code Read

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/verification/WebDiagnosticIntent.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/StaticVerificationRepairContext.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/ExecutionOutcome.java` equivalent checked as
  current CLI `ExecutionOutcome` implementation at
  `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`.
- `src/main/java/dev/talos/runtime/ApprovalPolicy.java`
- `src/main/java/dev/talos/runtime/ApprovalGate.java`
- `src/main/java/dev/talos/runtime/ScopeGuard.java`
- `src/main/java/dev/talos/runtime/TurnAuditCapture.java`
- `src/main/java/dev/talos/runtime/TurnPolicyTrace.java`
- `src/main/java/dev/talos/runtime/phase/ExecutionPhase.java`
- `src/main/java/dev/talos/runtime/phase/PhasePolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java`

## Planned Evidence

- Create `docs/architecture/02-runtime-policy-ownership-map.md`.
- Run `./gradlew.bat test --no-daemon`.

## Implementation Summary

- Created `docs/architecture/02-runtime-policy-ownership-map.md`.
- Mapped current policy ownership across the required runtime/orchestration
  classes.
- Assigned each responsibility to staged future policy classes under the
  `dev.talos.runtime.policy` direction.
- Identified `ProtocolSanitizationPolicy` as the safest first extraction
  because it is deterministic, recently covered by T13/T24/T27 regressions, and
  does not change permission authority.
- Listed behavior-preserving unit and e2e coverage required before extraction.
- No runtime code was changed.

## Work-Test Cycle Loop Used

Inner dev loop. This ticket did not declare a versioned candidate and did not
update `CHANGELOG.md`.

## Tests Run

```powershell
./gradlew.bat test --no-daemon
```

Result: PASS (`BUILD SUCCESSFUL`; task was up-to-date).

## Manual Talos Check Result

Not required. This ticket is docs-only and does not change runtime behavior.

## Known Follow-Ups

- Start the next implementation design sequence from T32/T33, unless T29 Qodana
  cleanup is selected first as a contained cleanup task.
- When policy extraction begins, use the map's first-extraction recommendation:
  extract protocol sanitization as a pure, behavior-preserving policy helper
  before touching permission or repair control.
