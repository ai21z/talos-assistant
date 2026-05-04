# Talos Capability Spine And Workspace Operations Architecture

Date: 2026-05-04
Branch: `v0.9.0-beta-dev`
Status: written for user review before ticket creation

## Purpose

Talos has crossed an important reliability milestone. The runtime now catches
many model mistakes that previously looked like successful work:

- wrong expected targets such as `script.js` versus `scripts.js`;
- failed or partial static verification;
- exact complete-file mismatches;
- unsupported binary document reads;
- post-command small-talk drift;
- stale or model-authored changed-files summaries.

The next phase should make Talos more useful as a general local workspace
assistant. That means more tools and more capabilities, but only if the
architecture can scale without losing the safety and trace discipline that made
the latest milestone possible.

This document defines that architecture.

The core decision is:

> Add a capability spine before adding many new tools.

Talos should not grow by bolting `mkdir`, `delete`, `move`, `run_command`, and
document tools directly into the current executor. Each capability must carry
runtime-owned metadata for risk, approval, checkpointing, evidence,
verification, output dominance, and trace.

## Current Product Identity

The current README gives the correct product direction:

> Talos is a local-first knowledge engine and workspace assistant.

In practical terms, Talos should become a controlled local workspace operator:

- understand a workspace;
- inspect and retrieve local context;
- create and edit files;
- organize folders and files;
- generate project artifacts such as docs, plans, reports, and scaffolds;
- verify work when capability exists;
- later, run approved local commands;
- keep the user in control through approval, sandboxing, checkpoints, and
  runtime-owned outcomes.

Talos is not just a chatbot, and not just RAG. Retrieval remains part of the
product, but the larger product is local workspace assistance.

## Architectural Verdict

Talos is architecturally pointed in the right direction, but it is not yet
clean enough to scale a large tool surface safely.

Strong foundations already exist:

- model backend SPI and provider-neutral request controls;
- managed llama.cpp backend with Ollama retained as legacy;
- tool registry and tool descriptors;
- read/write/destructive risk levels;
- workspace sandbox path resolution;
- approval gates;
- protected path policy;
- checkpoints before mutation;
- prompt debug and local turn traces;
- task contracts;
- action and evidence obligations;
- failure-dominant outcomes;
- static web capability, verification, and repair policy;
- active task context and changed-files summary context.

The weak points are also real:

- `AssistantTurnExecutor` is about 3370 lines and owns too much orchestration.
- Tools are still mostly a flat set of functions, not a typed capability
  catalog.
- Capability profiles are too narrow; static web exists, but generic workspace,
  docs, code-project, command, and document profiles do not.
- Evidence sufficiency is too coarse. The latest audit showed a model can list
  files and say it still needs to inspect them, while the runtime marks the turn
  answered.
- Protected-read postconditions are incomplete. The latest audit showed GPT-OSS
  can successfully read an approved `.env` and still refuse to answer, while the
  runtime marks the turn answered.
- Prompt-debug artifacts can persist approved protected content without a
  dedicated debug redaction/warning policy.
- Checkpointing is currently centered on one file mutation. Move, delete,
  rename, copy, and batch operations need bundle checkpoints.
- Shell/command execution is not available yet, and should not be added without
  a command policy.

The architecture should improve these weak points before the tool surface grows.

## External Architecture Alignment

The target architecture aligns with the main patterns used by serious local and
coding agents.

### OpenAI Codex

OpenAI Codex emphasizes explicit workspace controls, approval modes, protected
paths, command/network restrictions, and traceable agent actions.

Source:

- https://developers.openai.com/codex/agent-approvals-security

Talos already aligns on local workspace control, approval, protected paths, and
failure containment. Talos is weaker on command execution policy because it has
not implemented shell tools yet.

### OpenAI Agents SDK

OpenAI Agents SDK separates tools, guardrails, and tracing. Tool execution is
not just model text; it is part of an observable agent loop with validation
around inputs and outputs.

Sources:

- https://openai.github.io/openai-agents-js/guides/guardrails/
- https://openai.github.io/openai-agents-python/tracing/

Talos aligns on trace and policy direction. Talos should strengthen tool
guardrails by making capability metadata first-class.

### Claude Code

Claude Code exposes read, edit/write, shell, and other tools through
permission rules and modes. Its docs distinguish read-only operations from
write/shell operations, and they provide configuration for allowed and denied
tools.

Sources:

- https://code.claude.com/docs/en/permissions
- https://code.claude.com/docs/en/tools-reference
- https://code.claude.com/docs/en/settings

Talos aligns on approval-gated writes. Talos needs a more explicit permission
surface before it adds shell and destructive tools.

### Gemini CLI

Gemini CLI documents explicit filesystem tools and checkpointing before file
modification.

Sources:

- https://google-gemini.github.io/gemini-cli/docs/tools/file-system.html
- https://geminicli.com/docs/cli/checkpointing/

Talos already has read/write/edit and checkpoints. Talos needs first-class
folder, move, copy, delete, and batch operation support, plus bundle
checkpoints.

### Model Context Protocol

MCP treats tools as server-exposed actions with schemas, user consent, progress,
errors, annotations, and auditability. This is a useful internal discipline for
Talos even before Talos exposes MCP.

Source:

- https://modelcontextprotocol.io/specification/2025-06-18/server/tools

Talos should treat every tool as a typed operation with declared risk, input
schema, output shape, and trace semantics.

### OpenHands

OpenHands separates agent, tools, workspace, events, security validation, and
LLM responsibilities.

Source:

- https://docs.openhands.dev/sdk/arch/agent

Talos has many equivalent pieces, but `AssistantTurnExecutor` currently
centralizes too much of the agent loop.

### Aider

Aider's repo map and git integration show the importance of project
intelligence and reversible development workflows.

Sources:

- https://aider.chat/docs/repomap.html
- https://aider.chat/docs/git.html

Talos has retrieval and changed-files context, but not yet a full project map
or git-native development workflow.

## Design Principles

### Runtime Owns Control

The model can choose wording and propose actions, but Talos runtime owns:

- which tools are visible;
- which actions require approval;
- which paths are legal;
- which operations need checkpoints;
- which evidence is sufficient;
- which verification profile applies;
- whether the turn is complete, partial, blocked, or failed.

No required action should exist only as prompt wording.

### Capabilities Own Tool Semantics

A tool is not just a function name and JSON schema. Every tool must belong to a
capability and declare the operational facts Talos needs to safely expose it.

### Stronger Tools Require Stronger Policies

Adding power must not weaken control.

- Folder creation needs path policy and approval.
- Move/copy/rename needs source and destination policy.
- Delete needs destructive approval and checkpoint.
- Batch operations need a preview and bundle checkpoint.
- Shell needs command classification, working-directory limits, timeout,
  environment controls, and output limits.

### Evidence Is Capability-Specific

"Some tool was called" is not enough.

For example, static web diagnosis should not be satisfied by `list_dir` alone
when `index.html` is present. The capability profile should say which evidence
is enough.

### Outcome Dominance Remains Non-Negotiable

The final answer must be dominated by the strongest runtime fact:

- denied protected read beats model prose;
- invalid mutation beats model success text;
- failed verification beats completion claims;
- partial mutation remains partial;
- unsupported capability produces a capability note;
- approved read with successful evidence must not be classified as a generic
  refusal success.

### Decompose Without Big-Bang Rewrite

`AssistantTurnExecutor` is too large, but a full rewrite would be risky.

The path should be incremental:

1. add capability spine types;
2. migrate tool metadata;
3. move one policy boundary at a time out of the executor;
4. add new tools only through the new spine.

## Supported Capability Surface

Talos should define its product surface around these capability categories.

### INSPECT

Purpose:

- understand current workspace state without mutation.

Existing tools:

- `talos.list_dir`
- `talos.read_file`
- `talos.grep`
- `talos.retrieve`

Expected behavior:

- no file mutation;
- no protected content without approval;
- capability-specific evidence sufficiency;
- concise grounded answers.

Near-term improvement:

- static web diagnosis must read primary files, not only list them.

### CREATE

Purpose:

- create files, folders, and simple project/artifact structures.

Existing support:

- `talos.write_file` can create parent directories indirectly.

Missing first-class tools:

- `talos.mkdir`
- workspace scaffold operation;
- write-many or batch create.

Expected behavior:

- approval required;
- sandbox path enforcement;
- preview for multi-path operations;
- runtime-owned summary of created paths.

### EDIT

Purpose:

- modify existing files through targeted replacement or full rewrite.

Existing tools:

- `talos.edit_file`
- `talos.write_file`

Expected behavior:

- approval required;
- checkpoint before mutation;
- exact-write verification where applicable;
- static verifier when capability profile exists;
- failure-dominant output on mismatch.

### ORGANIZE

Purpose:

- move, copy, and rename files or folders inside the workspace.

Missing tools:

- `talos.move_path`
- `talos.copy_path`
- `talos.rename_path`

Expected behavior:

- approval required;
- source and destination sandbox checks;
- overwrite policy must be explicit;
- bundle checkpoint for multi-path effects;
- trace records source and destination.

### DELETE

Purpose:

- delete files or directories.

Missing tool:

- `talos.delete_path`

Expected behavior:

- destructive risk;
- explicit approval required;
- recursive delete requires stronger confirmation;
- bundle checkpoint before deletion when possible;
- protected paths blocked unless explicitly allowed by policy;
- final output must name deleted paths.

### VERIFY

Purpose:

- determine whether the workspace state satisfies the request.

Existing support:

- readback verification;
- exact content verification;
- static web verifier;
- changed-files runtime summary.

Missing support:

- generic verifier profile interface;
- command/test verifier later;
- capability-specific evidence sufficiency.

### EXECUTE

Purpose:

- run local commands such as tests, builds, formatters, and diagnostics.

Missing tool:

- `talos.run_command`

Expected behavior:

- not part of the immediate workspace operations milestone;
- separate command policy required before implementation;
- approval required by default;
- command risk classification;
- cwd constrained to workspace;
- timeout and output limits;
- environment redaction;
- no shell metacharacter bypass of file policies without command policy
  awareness.

### ARTIFACT

Purpose:

- create useful project artifacts such as Markdown docs, plans, findings,
  reports, and eventually structured binary documents.

Existing support:

- Markdown/text artifacts through `write_file`.

Near-term supported artifacts:

- `.md`
- `.txt`
- `.json`
- static web assets.

Deferred artifacts:

- `.docx`
- PDF;
- spreadsheets;
- slides.

Rule:

- Talos must not claim binary document inspection or generation unless a real
  capability/tool exists.

## Capability Spine

The new spine should introduce a small set of runtime types.

### `CapabilityKind`

Enum:

- `INSPECT`
- `CREATE`
- `EDIT`
- `ORGANIZE`
- `DELETE`
- `VERIFY`
- `EXECUTE`
- `ARTIFACT`

### `ToolOperationMetadata`

Every tool should declare:

- canonical tool name;
- capability kind;
- risk level;
- path roles;
- whether it mutates workspace;
- whether it can affect multiple paths;
- whether it requires approval;
- whether it requires checkpoint;
- whether it is destructive;
- whether it supports dry-run or preview;
- expected trace event kind;
- output summary kind;
- verifier hook id, if any.

Example:

```text
talos.mkdir
  capability: CREATE
  risk: WRITE
  mutatesWorkspace: true
  pathRoles: targetDirectory
  requiresApproval: true
  requiresCheckpoint: false
  destructive: false
  traceEvent: DIRECTORY_CREATED
  verifier: DIRECTORY_EXISTS
```

```text
talos.delete_path
  capability: DELETE
  risk: DESTRUCTIVE
  mutatesWorkspace: true
  pathRoles: targetPath
  requiresApproval: true
  requiresCheckpoint: true
  destructive: true
  traceEvent: PATH_DELETED
  verifier: PATH_ABSENT
```

### `CapabilityResolution`

Produced once per turn after task contract resolution.

Fields:

- selected `CapabilityKind`;
- artifact kind, if any;
- operation intent;
- expected target paths;
- protected target paths;
- allowed tool set;
- blocked tool set;
- evidence requirement;
- verification profile;
- approval mode;
- checkpoint mode;
- output dominance rule.

This is the bridge between task classification and tool exposure.

### `ToolSurfacePlanner`

Builds the visible tool list from:

- current turn plan;
- capability resolution;
- backend capability;
- permission policy;
- protected path policy;
- current repair/evidence state.

This should replace scattered ad hoc visible-tool decisions.

### `EvidenceSufficiencyPolicy`

Verifies that the gathered evidence is enough for the capability.

Examples:

- `LIST_DIRECTORY_ONLY`: requires `list_dir`, forbids content reads.
- `READ_TARGET_REQUIRED`: requires successful `read_file` for target.
- `STATIC_WEB_DIAGNOSIS`: if `index.html` exists, must read `index.html`; if
  linked JS/CSS files are relevant, should read those too or return evidence
  incomplete.
- `PROTECTED_READ_APPROVED`: if approval succeeds and read succeeds, final
  answer must not be a generic refusal.

### `WorkspaceOperationPlan`

Represents multi-path operations before execution.

Fields:

- operation id;
- operation kind;
- list of path changes;
- source and destination paths;
- overwrite policy;
- recursive flag;
- risk level;
- checkpoint requirements;
- approval summary;
- preview tree summary.

The first implementation can use this internally for batch operations only.
Later, Talos can expose a plan/preview UX.

### `WorkspaceOperationResult`

Structured runtime-owned result for workspace operations.

Fields:

- status: `APPLIED`, `PARTIAL`, `BLOCKED`, `FAILED`;
- changed paths;
- failed paths;
- skipped paths;
- checkpoint id;
- verification result;
- summary lines.

Outcome rendering should use this instead of model-authored success prose.

## AssistantTurnExecutor Decomposition

`AssistantTurnExecutor` should not be rewritten all at once. It should be
reduced by moving stable responsibilities into small services.

Target boundaries:

### `TurnPlanner`

Owns:

- task contract resolution;
- current turn plan creation;
- capability resolution;
- active context selection.

### `ToolSurfacePlanner`

Owns:

- native and prompt tool set;
- blocked tool set;
- provider request controls;
- repair/evidence constrained surfaces.

### `EvidenceGate`

Owns:

- evidence sufficiency;
- protected-read postconditions;
- unsupported capability postconditions.

### `WorkspaceOperationService`

Owns:

- mkdir/move/copy/rename/delete/batch operations;
- operation plans;
- bundle checkpoint interaction;
- operation result summaries.

### `OutcomeRenderer`

Owns:

- failure-dominant response shaping;
- partial mutation summaries;
- approved protected-read answer postconditions;
- changed-files runtime summaries.

### `TraceRecorder`

Owns:

- consistent event names;
- capability resolution events;
- tool exposure events;
- operation preview/apply events;
- evidence satisfied/unsatisfied events.

This decomposition keeps the executor as an orchestrator instead of a policy
container.

## Immediate Weak Points And How The Architecture Addresses Them

### Weak Point 1: Shallow Read-Only Diagnosis

Observed in T61-D:

- Qwen listed files, then said it needed to inspect files, but the runtime marked
  the turn `READ_ONLY_ANSWERED`.

Architecture fix:

- add capability-specific evidence sufficiency;
- static web diagnosis requires primary file reads;
- "I need to inspect next" after insufficient evidence becomes incomplete or
  triggers one bounded retry.

### Weak Point 2: Approved Protected Read Refusal

Observed in T61-D:

- GPT-OSS successfully read approved `.env`, then refused to answer.

Architecture fix:

- protected-read postcondition belongs in `EvidenceGate`/`OutcomeRenderer`;
- successful approved protected read cannot be marked complete if final answer
  is a generic refusal;
- runtime should render either the approved answer or a deterministic
  policy-owned explanation.

### Weak Point 3: Prompt-Debug Secret Persistence

Observed in T61-D:

- approved `.env` content appears in prompt-debug/provider-body artifacts.

Architecture fix:

- prompt-debug redaction policy must treat protected tool results as sensitive
  by default;
- optional explicit local debug mode can include protected content;
- prompt-debug saves should warn when protected content is included.

### Weak Point 4: Flat Tools

Current state:

- tool descriptors declare name, schema, description, and risk;
- they do not declare full capability metadata.

Architecture fix:

- add `ToolOperationMetadata`;
- tool surface and approval logic consume metadata;
- new tools cannot enter the surface without metadata.

### Weak Point 5: Single-File Checkpoint Bias

Current state:

- checkpoint capture is centered around one target path.

Architecture fix:

- add bundle checkpoint support before destructive or multi-path tools;
- move/copy/rename/delete and batch operations record all affected paths.

### Weak Point 6: Future Shell Risk

Current state:

- no command execution tool yet.

Architecture fix:

- do not add shell as a normal workspace tool;
- design `CommandPolicy` first;
- classify command risk, cwd, timeout, environment, network, output limits, and
  write effects.

## Ticket Breakdown

This sequence starts with current weak points, then adds the architecture needed
for new tools, then adds workspace operations.

### T123 - Read-Only Evidence Sufficiency For Static Workspace Diagnosis

Severity: high/medium

Scope:

- Static web or obvious workspace diagnosis must not be satisfied by
  `list_dir` alone when primary files are present.
- If the model answers "I need to inspect" after only listing, Talos should mark
  the turn evidence-incomplete or do one bounded evidence retry.

Acceptance:

- Test Qwen-shaped case: `list_dir` then prose "I need to inspect" does not
  become `READ_ONLY_ANSWERED`.
- Static web diagnosis reads `index.html` at minimum when present.
- Existing names-only/list-only prompts still remain list-only and do not read
  content.

### T124 - Approved Protected Read Answer Postcondition

Severity: high/medium

Scope:

- If a protected read is approved and `read_file` succeeds, generic model
  refusal should not be accepted as a completed answer to the user's request.
- Runtime should render approved content when policy allows, or a
  deterministic policy-owned explanation if it cannot.

Acceptance:

- Test GPT-OSS-shaped case: successful `.env` read followed by "I'm sorry, but
  I can't provide that" is not `READ_ONLY_ANSWERED`.
- Denied protected read remains blocked with no content.
- Approved protected read answer remains local-only and traceable.

### T125 - Prompt-Debug Protected Content Redaction Policy

Severity: medium

Scope:

- Prompt-debug saves should redact protected tool-result content by default or
  clearly require an explicit include-protected mode.
- Provider-body debug artifacts should not silently persist approved secrets.

Acceptance:

- Protected tool result content is redacted in default prompt-debug artifacts.
- A local opt-in mode, if added, clearly labels protected content inclusion.
- Existing prompt-debug usefulness is preserved for non-protected content.

### T126 - Capability Spine Core Types

Severity: high

Scope:

- Add `CapabilityKind`, `ToolOperationMetadata`, and `CapabilityResolution`.
- No behavior change required beyond metadata availability.

Acceptance:

- Existing tools can expose metadata.
- Metadata includes capability, risk, mutatesWorkspace, path roles,
  approval/checkpoint requirements, and trace event kind.
- Tests verify metadata for existing tools.

### T127 - Tool Metadata Migration And Tool Surface Planner

Severity: high

Scope:

- Migrate existing tool-surface decisions to consume capability metadata.
- Introduce `ToolSurfacePlanner` as a service boundary.

Acceptance:

- Existing read/write tool visibility behavior remains unchanged.
- Repair/evidence constrained surfaces still work.
- Prompt audit still reports native and prompt tools.
- `AssistantTurnExecutor` loses some direct tool-surface responsibility.

### T128 - Workspace Operation Plan And Bundle Checkpoint Design

Severity: high

Scope:

- Add internal `WorkspaceOperationPlan` and `WorkspaceOperationResult`.
- Add bundle checkpoint support or a compatible abstraction for multi-path
  operations.

Acceptance:

- Tests cover planned multi-path operations without applying them.
- Bundle checkpoint can represent source/destination/deleted paths.
- Single-file checkpoints continue working.

### T129 - Workspace Operations V1

Severity: high

Scope:

- Add first-class workspace tools:
  - `talos.mkdir`
  - `talos.move_path`
  - `talos.copy_path`
  - `talos.rename_path`
- Consider `talos.delete_path` only if bundle checkpoint and destructive
  approval are ready.

Acceptance:

- All paths remain sandboxed inside workspace.
- Approval required for write/organize operations.
- Runtime-owned summary lists created/moved/copied/renamed paths.
- Tests cover path traversal, overwrite handling, and failure-dominant output.

### T130 - Batch Workspace Apply

Severity: medium/high

Scope:

- Support coherent multi-file/folder operations with one approval.
- Preview operation summary before applying.

Acceptance:

- One approval can apply a coherent batch.
- Partial failure reports exact applied and failed paths.
- Bundle checkpoint id is recorded.

### T131 - AssistantTurnExecutor Decomposition Phase 1

Severity: high

Scope:

- Extract one or more stable services from `AssistantTurnExecutor`:
  - `TurnPlanner`;
  - `EvidenceGate`;
  - `OutcomeRenderer`;
  - or `ToolSurfacePlanner`, depending on T127 state.

Acceptance:

- No behavior regression.
- File size/responsibility meaningfully reduced.
- Extracted service has focused tests.

### T132 - Command Execution Architecture Design

Severity: medium

Scope:

- Design, but do not yet implement, approval-gated command execution.
- Define command risk classification, allow/deny policy, cwd limits, timeouts,
  output caps, environment redaction, and checkpoint rules.

Acceptance:

- Written design approved before any `run_command` implementation.
- Ticket sequence for command execution exists.

## Release Gates

Before adding shell/command execution:

- T123 and T124 should be fixed or intentionally deferred.
- Capability metadata should exist for all tools.
- Tool surface planning should consume capability metadata.
- Bundle checkpoint design should be clear.

Before adding destructive delete:

- destructive approval language must be explicit;
- bundle checkpoint must be available or deletion must be deliberately limited;
- tests must cover recursive and protected-path cases.

Before adding binary document support:

- real document parser/generator tools must exist;
- unsupported capability note must remain the default when the tool is absent;
- no model-authored fake binary summaries.

## Success Criteria

The architecture is successful when:

- a new tool can be added by declaring metadata and implementing a focused
  executor, without editing broad prompt/outcome logic in many places;
- every tool has a known capability, risk, approval, checkpoint, trace, and
  verification story;
- evidence sufficiency is capability-specific;
- `AssistantTurnExecutor` shrinks over time;
- audit findings distinguish model weakness from runtime failure;
- Talos can create folders, organize workspaces, and create useful docs without
  becoming less safe.

## Final Recommendation

Proceed in this order:

1. Fix the two current T61-D correctness gaps: read-only evidence sufficiency
   and approved protected-read postcondition.
2. Add prompt-debug protected-content redaction.
3. Add the capability spine core types.
4. Migrate existing tools to capability metadata and a `ToolSurfacePlanner`.
5. Add workspace operation planning and bundle checkpoints.
6. Add workspace operation tools.
7. Decompose `AssistantTurnExecutor` incrementally.
8. Design command execution only after the workspace operation layer is stable.

This keeps Talos aligned with its name: a strong local assistant, built from
controlled, observable, durable parts rather than prompt luck.
