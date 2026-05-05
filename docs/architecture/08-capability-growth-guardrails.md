# Capability Growth Guardrails And Refactoring Map

Date: 2026-05-05
Branch: `v0.9.0-beta-dev`
Status: active architecture guardrail

## Purpose

Talos is growing from a local-first knowledge engine into a controlled local
workspace assistant. More tools are useful only if they preserve the runtime
discipline that already exists: approval, protected paths, checkpoints,
evidence obligations, verification, failure-dominant output, prompt debug, and
local traces.

This document defines the rules for adding capabilities without recreating the
current coupling pressure in large classes.

It is not an implementation plan for a large rewrite. It is the map that future
implementation tickets must follow.

## Current Pressure Points

The largest source files on this branch are:

| File | Current role | Risk |
|---|---|---|
| `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` | turn orchestration, prompt assembly, retry control, handoffs, output shaping integration | god-class pressure; new capabilities should not be added here by default |
| `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` | final answer shaping and task outcome classification | truth policy, privacy containment, verification wording, and domain output are too close together |
| `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java` | static verification for web and file outcomes | valuable static-web capability, but generic verifier ownership is too broad |
| `src/main/java/dev/talos/runtime/TurnProcessor.java` | tool execution, approval, checkpoint, sandbox integration | side effects and policy boundaries need clearer ports before more tools land |

These files are allowed to receive small integration calls, but new capability
logic should be placed behind owned policy/profile/service classes.

## Dependency Direction

Talos should keep this dependency direction:

```text
cli/repl and cli/modes
  -> runtime turn orchestration
  -> runtime policy/profile/verification/repair/outcome
  -> tools and engine SPI
  -> core utilities/security/config
```

Rules:

- `cli/modes` may orchestrate but must not own capability-specific rules.
- `runtime/policy` owns deterministic policy decisions.
- `runtime/toolcall` owns tool-loop mechanics and action-obligation control.
- `runtime/verification` owns verifier contracts and profile selection.
- `runtime/repair` owns repair decisions and repair-profile state.
- `runtime/outcome` owns machine-readable outcome facts and warning types.
- `runtime/trace` owns trace schemas and redaction summaries.
- `tools` own narrow tool execution only; tools do not decide turn completion.
- `engine/*` owns backend protocol translation only; engines do not decide
  Talos task semantics.
- `core/security` owns reusable redaction, sandbox, and path-safety primitives.

No lower layer should call back into `AssistantTurnExecutor`.

## Design Rules

### Runtime Owns Control

Required behavior must be runtime state, not only prompt text.

Use runtime state for:

- action obligations;
- evidence obligations;
- expected target scope;
- approval and protected path policy;
- checkpoint requirements;
- verification requirements;
- final outcome classification.

Prompt wording can make the model more likely to comply, but it is not the
enforcement surface.

### Capabilities Own Semantics

Every new capability must declare:

- artifact kinds it understands;
- operations it supports;
- target extraction rules;
- visible tools;
- approval and risk level;
- checkpoint behavior;
- evidence requirements;
- verifier profile;
- repair profile;
- trace fields;
- output dominance rules.

Do not add a new tool as only a `ToolRegistry.register(...)` plus prompt text.

### Side Effects Stay Behind Ports

Filesystem, process, network, and model calls should sit behind narrow ports.

Use ports/adapters when code crosses one of these boundaries:

- model backend protocol;
- filesystem mutation;
- command/process execution;
- checkpoint capture/restore;
- document parsing;
- persistent session or trace storage;
- future MCP/server integration.

Adapter code may translate formats. It must not own policy decisions such as
"this turn is complete" or "this protected content may be shown."

### Prefer Policy Objects For Deterministic Rules

Use policy objects when the decision is deterministic and testable:

- `ProtectedPathPolicy`
- `EvidenceObligationPolicy`
- `ActionObligationPolicy`
- future `CapabilitySelectionPolicy`
- future `CommandPermissionPolicy`
- future `WorkspaceOperationPolicy`

Policy objects should return explicit records or enums, not free prose that
callers need to parse.

### Prefer Strategy Profiles For Capability Variation

Use strategy/profile objects when behavior varies by capability:

- verifier profile;
- repair profile;
- tool-surface profile;
- prompt-frame profile;
- output-summary profile.

Static web, workspace operations, document capability checks, and command
execution should be separate profiles rather than branches inside one generic
class.

### Use Command Pattern For Workspace Operations

Folder creation, move, copy, rename, delete, and batch apply should be modeled
as operation commands with:

- normalized source and destination paths;
- risk classification;
- approval text;
- checkpoint plan;
- dry-run or preview summary when useful;
- execution result;
- trace event.

The command object is the unit of approval, checkpointing, execution, and trace.

### Use Immutable Records For Runtime Facts

Runtime facts should be immutable records whenever practical:

- capability selection;
- tool operation metadata;
- action/evidence obligation result;
- checkpoint plan;
- verification result;
- repair plan;
- final outcome facts.

Mutable state is acceptable only inside bounded orchestration objects such as a
single tool-loop state or one command execution transaction.

### Keep Side-Effect Boundaries Thin

Tool implementations should:

- validate inputs;
- use sandbox/path helpers;
- perform the action;
- return structured success or failure.

Tool implementations should not:

- inspect chat history;
- infer user intent;
- decide completion;
- shape final assistant output;
- suppress privacy-sensitive prose.

## First Extraction Map

The first refactors should reduce `AssistantTurnExecutor` without changing
behavior.

Allowed first seams:

| Proposed owner | Extract from | Responsibility |
|---|---|---|
| `TurnPreparationService` | setup branches in `AssistantTurnExecutor` | build `CurrentTurnPlan`, history policy, active context inputs, and prompt-audit summary |
| `PromptAssemblyService` | prompt/message assembly branches | assemble system/current-turn/repair messages and prompt-debug metadata |
| `ModelTurnRunner` | model call dispatch branches | call streaming/non-streaming LLM paths and normalize model response shape |
| `ReadEvidenceHandoffController` | protected/public read handoff methods | deterministic no-tool read recovery and approval handoff |
| `MutationRetryController` | mutation retry and failure-obligation branches | fresh mutation retry, no-tool mutation breach, and retry-budget state |
| `OutcomeRenderingService` | final outcome integration call sites | invoke outcome policy/rendering and record trace outcome |
| `CapabilityProfileRegistry` | scattered task/tool/verifier selection | choose capability, tool profile, evidence profile, verifier, and repair profile |

Extraction rule:

- Move one behavior-preserving slice at a time.
- Keep old tests green before and after each slice.
- Do not combine extraction with new user-visible behavior unless the ticket
  explicitly permits it.

## Verification And Repair Map

`StaticTaskVerifier` should not grow new domains.

Allowed near-term direction:

- keep static web checks intact;
- extract static web verification into a `StaticWebVerifier` profile;
- introduce a small verifier registry;
- let task/capability profiles choose verifier applicability;
- keep verifier results as `TaskVerificationResult`.

Forbidden in capability tickets:

- adding document, command, or workspace-operation verification branches inside
  `StaticTaskVerifier`;
- broad rewrites of static web checks while adding unrelated tools;
- model-based verification for safety-critical completion.

`RepairPolicy` should follow the same profile split:

- static web repair stays profile-owned;
- full-rewrite repair rules stay deterministic;
- stale edit reread rules stay tool-loop owned;
- future document/command/workspace repairs get their own profiles.

## Outcome Map

`ExecutionOutcome` is already enforcing important truth and privacy guarantees.
Do not bypass it.

Allowed near-term direction:

- extract typed warning and postcondition helpers;
- move domain-specific summaries into profile-owned renderers;
- keep machine-readable `TaskOutcome` and `TruthWarningType` as the stable
  contract;
- keep failure-dominant and privacy-dominant output runtime-owned.

Forbidden:

- final output success claims from model text after failed verification;
- capability-specific completion claims outside outcome policy;
- prompt-debug or trace paths that persist protected content by default.

## Refactor Scope Rules

Each capability ticket may include small refactors only when they directly
support the capability boundary.

Allowed:

- extracting a pure policy/helper with focused tests;
- adding a record/enum for a runtime fact;
- adding a profile interface plus one existing implementation;
- moving code without behavior change and keeping tests equivalent;
- adding trace fields needed by the new capability;
- adding ticket-specific architecture metadata.

Forbidden:

- changing the Java baseline;
- rewriting `AssistantTurnExecutor` broadly;
- introducing dynamic plugins or MCP behavior without an approved ticket;
- adding shell/browser/network tools as incidental dependencies;
- weakening approval, protected path, checkpoint, trace, or verification policy;
- adding prompt-only obligations for required actions;
- mixing large code movement with behavior changes.

## Ticket Architecture Metadata

Every future tool or capability ticket must state:

- Capability:
- Operation(s):
- Owning package/class:
- New or changed tools:
- Risk level:
- Approval behavior:
- Protected path behavior:
- Checkpoint behavior:
- Evidence obligation:
- Verification profile:
- Repair profile:
- Outcome/truth warnings:
- Trace/debug fields:
- Refactor scope:
- Non-goals:

If any item is "none", the ticket must explain why.

## Next Architecture Sequence

The current open tickets should follow this order unless new evidence changes
the priority:

1. Java migration readiness spike stays separate from behavior work.
2. Add capability-spine core types.
3. Migrate tool metadata into capability/tool-operation metadata.
4. Add workspace operation planning and bundle checkpoints.
5. Add workspace operation tools.
6. Add batch workspace apply only after operation commands/checkpoints exist.
7. Start `AssistantTurnExecutor` decomposition after the capability spine gives
   the extracted services stable input/output records.
8. Design command execution separately before any shell tool is exposed.

This order keeps capability growth ahead of tool power.
