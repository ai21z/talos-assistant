# [T335-done-high] Architecture Hygiene Baseline And Refactor Sequence

Status: done
Priority: high
Date: 2026-05-21
Branch: `v0.9.0-beta-dev`
Commit inspected: `c32957e95925168947b46e60a393e09091d90bb3`
Candidate version: `talosVersion=0.9.9`

## Evidence Summary

- Source: static source audit, architecture docs, existing reports, and five
  read-only parallel audit lanes.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` /
  `c32957e95925168947b46e60a393e09091d90bb3`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout on `v0.9.0-beta-dev`.
- Raw transcript path: none; no Talos transcript was produced.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: documentation-only baseline and ticket.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: static docs checks only.

## Problem

Talos has passed many runtime hardening milestones, but the codebase now needs
architecture hygiene before broad dependency injection or refactor work begins.
The central risk is not lack of architecture language. The central risk is that
several safety-critical mechanisms still depend on large classes cooperating in
fragile order:

- package boundaries are not enforced;
- runtime/core import CLI concepts;
- core/runtime/tools form cycles;
- `AssistantTurnExecutor`, `TurnProcessor`, `StaticTaskVerifier`,
  `ToolCallRepromptStage`, `ExecutionOutcome`, and `TaskContractResolver`
  remain high-blast-radius policy owners;
- some release evidence lanes can still overclaim when results are missing or
  stale;
- CLI slash-command mutations are not routed through one common mutation
  evidence policy.

## Goal

Create an evidence-backed architecture hygiene baseline that names concrete
findings, refactor order, test gates, and non-goals before any runtime code
movement starts.

## Non-Goals

- No runtime refactor in T335.
- No DI framework.
- No Spring/Guice/container migration.
- No DDD/BDD ceremony.
- No broad package move.
- No behavior change.
- No live audit.
- No version bump.
- No generated audit artifact commits.

## Implementation Summary

Created:

- `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`

The report records:

- branch, commit, and candidate version provenance;
- five static audit lanes;
- local largest-file and dependency-direction inventory;
- package boundary violations;
- policy ownership findings;
- verification, repair, and outcome findings;
- CLI, REPL, and composition findings;
- release evidence integrity findings;
- external reference cross-checks;
- a staged refactor sequence;
- the next recommended implementation ticket.

## Classification

Primary taxonomy bucket:

- `PERMISSION`
- `VERIFICATION`
- `OUTCOME_TRUTH`
- `REPAIR_CONTROL`
- `TOOL_SURFACE`
- `TRACE_REDACTION`

Secondary buckets:

- package boundary enforcement
- dependency injection seams
- release evidence integrity
- CLI mutation governance

Blocker level:

- candidate follow-up for code hygiene
- release blocker only where specific evidence findings overlap existing open
  release-evidence tickets such as T333

Why this level:

No P0 runtime behavior was proven from static evidence alone. The confirmed
problem is P1 architecture risk: too many trust decisions rely on large classes
and unenforced dependency direction.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Refactor Talos with dependency injection.
```

Architectural hypothesis:

```text
Talos needs boundary ratchets and behavior-preserving policy extraction before
any large dependency injection cleanup. The first useful implementation is an
architecture import scanner / package-boundary test that prevents new cycles
while current cycles are burned down deliberately.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/cli/repl/Context.java`
- `build.gradle.kts`
- `tools/manual-eval/run-talosbench.ps1`

Why a one-off patch is insufficient:

The same coupling pattern appears across runtime orchestration, verification,
tool execution, CLI composition, and release evidence generation. Fixing one
method does not prevent the next ticket from adding the same dependency edge or
policy branch elsewhere.

## Architecture Metadata

Capability:

- Architecture hygiene and refactor governance.

Operation(s):

- Static source inspection.
- Documentation.
- Future validation/test gate planning.

Owning package/class:

- Future implementation should start in build/test architecture validation,
  not in production runtime code.

New or changed tools:

- None in T335.

Risk, approval, and protected paths:

- Risk level: high architecture risk, low immediate runtime risk.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: T335 creates source-backed architecture evidence.
- Verification profile: static docs/build hygiene checks.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed in T335: documentation-only baseline.
- Forbidden broad rewrites: production code movement, DI framework adoption,
  package moves, permission/approval/checkpoint behavior changes.

## Acceptance Criteria

- Architecture hygiene baseline report exists.
- Baseline names branch, commit, candidate version, and dirty-state caveat.
- Baseline includes dependency-direction evidence.
- Baseline includes policy ownership evidence.
- Baseline includes verifier/repair/outcome evidence.
- Baseline includes CLI/composition evidence.
- Baseline includes release-evidence gate findings.
- Baseline proposes a staged refactor order.
- Baseline names the next implementation ticket.
- No runtime behavior changes are included.

## Result

Acceptance criteria satisfied by:

- `work-cycle-docs/reports/t335-architecture-hygiene-baseline-20260521.md`

The next recommended implementation ticket is:

```text
T336 - Architecture boundary ratchet and package import scanner
```

T333 remains the most urgent release-evidence integrity ticket if the immediate
goal shifts back to release-audit readiness.

## Tests / Evidence

Required for this documentation-only ticket:

```powershell
git diff --check
.\gradlew.bat validateReleaseLedger --no-daemon
```

No full `check` is required for T335 because it does not change production,
test, build, or runtime behavior.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The line-count and package-edge inventory can drift quickly after runtime
  refactors. T336 should turn the most important parts into machine-enforced
  guardrails.
- Existing package cycles are real; a strict no-cycle rule will fail
  immediately unless introduced with a baseline/ratchet strategy.
- Release evidence cleanup and architecture cleanup overlap but should not be
  mixed into one broad patch.

## Known Follow-Ups

- T336: architecture boundary ratchet and package import scanner.
- Follow-up: runtime/core CLI dependency split.
- Follow-up: `ToolExecutionPolicyPipeline`.
- Follow-up: `WorkspaceOperationStaticVerifier` extraction.
- Follow-up: structured `RepairPlan` instead of repair prose parsing.
- Follow-up: ranked `OutcomeSignal` model.
- Follow-up: CLI mutation service for prompt-debug/setup/session writes.
