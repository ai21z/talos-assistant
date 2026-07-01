# T59 Active Task Context And Artifact Goal Design

Date: 2026-04-30

Status: written for user review before implementation planning

Ticket: `work-cycle-docs/tickets/open/[T59-open-high] active-task-context-and-artifact-goal.md`

## Goal

Give Talos a small runtime-owned active task state so natural follow-ups can
continue the user's current work without broad guessing from chat history.

The first useful win is narrow and practical:

1. User asks Talos to propose changes to a specific artifact without editing.
2. Talos answers with a proposal.
3. User says `make those changes`.
4. Talos carries the prior target and proposed operation into the next turn
   plan, exposes the right tool surface, and records the context in prompt
   audit and `/last trace`.

The principle is: do not cut off the user's task and do not force a terminal
restart. T59 must improve live-session continuity. Broader memory, context
pressure prompts, compaction UX, and vector retrieval are intentionally separate
future concerns.

## Research Summary

The current best pattern is not "put everything in memory." Reputable agent
systems split context into layers:

- OpenAI documents conversation state as either manually chained messages or
  persisted conversation/response state, while warning that the context window is
  a hard token budget including input, output, and reasoning tokens:
  https://developers.openai.com/api/docs/guides/conversation-state
- OpenAI compaction is a separate mechanism for long-running interactions. It
  reduces context size while preserving needed state, but it is not the same
  thing as task memory:
  https://developers.openai.com/api/docs/guides/compaction
- OpenAI prompt caching can reduce repeated-prefix cost and latency, but it does
  not reduce the amount of context the model must reason over:
  https://developers.openai.com/api/docs/guides/prompt-caching
- local coding assistant treats the context window as everything loaded into a session,
  including files, instructions, hidden tool context, and compaction summaries.
  Its documentation separates loaded rules, memories, subagent summaries, and
  compaction behavior:
  https://code.external assistant.com/docs/en/context-window
- model provider's tool context guidance separates tool search, programmatic tool
  calling, prompt caching, and context editing. Each targets a different source
  of context pressure:
  https://platform.external assistant.com/docs/en/agents-and-tools/tool-use/manage-tool-context
- Gemini CLI checkpointing separately saves project state, conversation history,
  and the tool call being attempted before file modifications:
  https://google-gemini.github.io/gemini-cli/docs/cli/checkpointing.html

The implication for Talos is clear: T59 should be typed control-plane state,
not general memory. Vector search is the wrong first solution because T59 needs
deterministic continuity for the current task, not fuzzy retrieval across a
large knowledge base. Vectors may become useful later for large document or code
retrieval, but they should not authorize mutations or carry task intent.

## User-Approved Scope

T59 implements the smallest useful active context layer:

- one active task at a time;
- bounded target, operation, proposal, outcome, and verifier summaries;
- deterministic activation only for narrow follow-up phrases;
- no model-authored state overriding runtime policy;
- prompt audit and `/last trace` visibility;
- live-session operation without asking the user to close or reopen Talos.

T59 does not implement:

- a full context pressure warning menu;
- user-choice UX for clearing or compacting context;
- automatic transcript compaction;
- vector database memory;
- long-term project memory;
- dynamic capability registry;
- broad semantic inference from vague follow-ups.

## Approaches Considered

### Recommended: Small Runtime-Owned Active Context

Store one compact `ActiveTaskContext` and one compact `ArtifactGoal` in Talos
runtime/session state. Use deterministic policy to decide whether the current
user request may consume, suppress, expire, or clear that state.

Benefits:

- directly solves proposal followed by `make those changes`;
- keeps context prompt injection tiny and auditable;
- works with existing `CurrentTurnPlan`, prompt audit, and trace fields;
- gives future compaction work a stable state object outside lossy chat
  summaries;
- avoids turning every follow-up into a broad workspace search.

Cost:

- needs careful clearing and expiration rules;
- needs tests proving stale context cannot override explicit current intent.

### Alternative: Transcript Reconstruction Only

Keep using chat history and improve `TaskContractResolver` phrase matching.

Benefits:

- small code change;
- no new state model.

Cost:

- keeps the exact T54 weakness: the model and resolver must reconstruct target
  and operation from prose;
- encourages broad reads when a compact target should be enough;
- makes prompt audit less useful because the active work is not a typed runtime
  fact.

### Alternative: Semantic Or Vector Memory

Persist embeddings of prior turns, artifacts, proposals, and traces, then
retrieve related snippets for follow-ups.

Benefits:

- could help later with large project knowledge or document retrieval.

Cost:

- too expensive and nondeterministic for T59;
- introduces privacy, storage, ranking, and latency concerns;
- does not solve authorization or mutation safety;
- can retrieve plausible but stale context and make the outcome worse.

## Architecture

T59 should add a small task-continuity layer between conversation memory and the
current-turn plan.

```text
completed turn
  -> ActiveTaskContextUpdater
  -> SessionMemory / SessionData compact state

next user request
  -> TaskContractResolver
  -> ActiveTaskContextPolicy
  -> CurrentTurnPlan(activeTaskContext, artifactGoal, verifierProfile)
  -> CurrentTurnCapabilityFrame + PromptAuditSnapshot + /last trace
  -> execution and outcome policies
```

The current repo already has placeholders for `activeTaskContext`,
`artifactGoal`, and `verifierProfile` in `CurrentTurnPlan` and
`PromptAuditSnapshot`. T59 should make those placeholders runtime-owned facts.

## State Model

### ActiveTaskContext

`ActiveTaskContext` is a compact value object, not a planner and not memory.

Suggested fields:

- `schemaVersion`
- `state`: `NONE`, `ACTIVE`, `SUPPRESSED`, `CLEARED`, `EXPIRED`
- `kind`: `PROPOSED_CHANGES`, `VERIFIER_FINDINGS`, `DENIED_MUTATION`,
  `PARTIAL_MUTATION`, `VERIFIED_MUTATION`
- `sourceTurnNumber`
- `sourceTraceId`
- `updatedTurnNumber`
- `expiresAfterTurnNumber`
- `targets`
- `operation`: `PROPOSE_EDIT`, `APPLY_EDIT`, `REPAIR`, `CREATE`, `VERIFY`,
  `ANSWER_ONLY`
- `proposalSummary`
- `previousOutcomeStatus`
- `verifierFindings`
- `blockedReason`
- `suppressionReason`

V1 limits:

- exactly one active context;
- expires after 3 user turns unless refreshed;
- at most 5 target paths;
- at most 600 characters of proposal summary in stored state;
- at most 5 verifier findings;
- at most 500 characters of verifier findings in stored state;
- prompt-rendered active context target: 120 to 220 tokens;
- prompt-rendered active context hard cap: about 250 tokens or 1200
  characters;
- no raw full-file content and no full diff text in active context.

### ArtifactGoal

`ArtifactGoal` describes the artifact and operation implied by the active work.
It is intentionally smaller than a future capability profile.

Suggested fields:

- `artifactKind`: `README`, `MARKDOWN`, `STATIC_WEB`, `GENERIC_FILE`,
  `UNKNOWN`
- `operation`: `PROPOSE_EDIT`, `APPLY_EDIT`, `REPAIR`, `CREATE`, `VERIFY`
- `targets`
- `verifierProfile`
- `source`: `CURRENT_REQUEST`, `ACTIVE_CONTEXT`, `TRACE_OUTCOME`

For T59, `ArtifactGoal` should be good enough to carry a README proposal into a
follow-up edit and to expose verifier findings after a failed verification. It
should not own static-web-specific repair logic; that belongs to later
capability profile work.

## Update Rules

The updater runs after a turn completes and inspects deterministic turn facts:
user input, `CurrentTurnPlan`, final outcome, prompt audit/local trace, tool
outcomes, and final assistant text.

It should update active context only when the runtime has enough evidence:

- propose-only turn with concrete targets and no mutations:
  create `ACTIVE/PROPOSED_CHANGES`;
- verification failure:
  create or refresh `ACTIVE/VERIFIER_FINDINGS`;
- approval denial for mutation or protected access:
  create `ACTIVE/DENIED_MUTATION` with `blockedReason` and `no files changed`;
- partial mutation:
  create `ACTIVE/PARTIAL_MUTATION` with changed and unresolved targets when
  trace evidence supports it;
- verified successful mutation:
  clear the proposal context or replace it with a compact
  `VERIFIED_MUTATION` summary only for immediate "what changed?" style
  follow-ups.

The updater must not parse raw model prose as the source of authority when a
runtime field or trace field exists. Model text may provide a compact proposal
summary, but targets, operation, mutation status, and verification status must
come from deterministic policy and trace data.

## Consumption Rules

At the start of each user turn, `ActiveTaskContextPolicy` decides whether the
saved context applies to the current request.

Use context when:

- the saved context is `ACTIVE`;
- it is not expired;
- the current request is a narrow follow-up such as `make those changes`,
  `apply those changes`, `go ahead and apply`, or `yes, apply it`;
- the saved context has concrete targets and operation;
- the current request does not name a conflicting target or a new task.

Suppress context when:

- the current request is small talk, acknowledgement, model chat, privacy chat,
  or no-workspace chat;
- the current request explicitly says not to inspect or modify workspace files;
- the current request is a slash-command or command-like help request.

Ignore or clear context when:

- the user names a new explicit target unrelated to the active target;
- the user asks for a distinct new task;
- the context has expired;
- the active target no longer exists and the current request is not a repair or
  recreate request.

Do not treat a bare `yes` as mutation approval unless the previous runtime state
contains a precise approval question and the active context has concrete targets.
This keeps natural flow possible without making every acknowledgement dangerous.

## CurrentTurnPlan Integration

T59 should populate the existing plan fields instead of creating a second prompt
contract:

- `activeTaskContext`: compact rendered state such as
  `ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT sourceTrace=<id> summary=<redacted preview>`;
- `artifactGoal`: compact rendered artifact goal such as
  `README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT`;
- `verifierProfile`: existing static verifier profile or
  `NONE_OR_NOT_DERIVED`.

`CurrentTurnCapabilityFrame.render(plan)` should include these fields when
present and add short guidance:

- active context is a hint for this turn only;
- explicit current user instructions win over active context;
- use active targets for deictic follow-ups;
- do not broaden to unrelated workspace files because context is present.

Prompt audit and `/last trace` must show presence, suppression, expiration, or
absence. This is part of the feature, not debug polish.

## Persistence

T59 should store active context in live `SessionMemory` so the user can continue
within the same CLI session without restarting.

It should also extend session snapshot persistence with a compact active context
object, keeping the schema change small and backward-compatible:

- add nullable-safe active context and artifact goal fields to `SessionData`;
- read missing fields as `NONE`;
- write compact JSON, not raw transcript fragments;
- persist only bounded/redacted state;
- treat JSON load failures or schema mismatches as `NONE`, never as fatal.

This is not a full session-resume memory feature. It is only a small durable
state object that gives future compaction and resume work something structured
to preserve.

## Safety Rules

- Current user intent wins over active context.
- Active context may resolve a deictic target; it may not authorize protected
  reads, broad reads, or arbitrary mutation.
- Runtime policy, not model prose, owns mutation permission, evidence
  obligations, outcome status, and active-context activation.
- Stale context is worse than no context. Expiration and clearing are required
  behavior.
- No-workspace and privacy turns must suppress active context.
- Active context should never store full file contents, secrets, or large diffs.
- Prompt audit uses existing redaction/preview behavior and compact caps.
- If active context is malformed, expired, or ambiguous, Talos should ask for a
  target or ignore context rather than guessing.

## User-Visible Behavior

For the target T59 flow:

```text
User: Please propose a better README. Do not edit yet.
Talos: ...proposal...
User: make those changes
```

The real user should notice:

- less repeated explanation;
- fewer broad workspace reads;
- the follow-up targets the same file and operation;
- `/last trace` explains why the follow-up inherited context.

The user should not notice:

- any new memory-management prompt;
- terminal restart requirements;
- vector indexing delays;
- broad "remember everything" behavior.

## Testing Strategy

Use test-driven implementation after this spec is approved.

Required unit tests:

- active context update after a propose-only answer;
- suppression for no-workspace and privacy turns;
- explicit unrelated target ignores or clears previous context;
- expiration after 3 user turns;
- deictic apply phrase consumes active proposal context;
- malformed or missing persisted context loads as `NONE`.

Required plan/frame/audit tests:

- `CurrentTurnPlan` contains bounded active context and artifact goal strings;
- `CurrentTurnCapabilityFrame` renders active context guidance;
- `PromptAuditSnapshot.renderCompact()` shows active context presence,
  suppression, expiration, or absence.

Required executor/e2e tests:

- propose README changes without editing, then apply via `make those changes`;
- follow-up after static verification failure references previous verifier
  findings without broad workspace guessing;
- follow-up after approval denial records that no files changed.

Required TalosBench coverage:

- proposal plus follow-up case;
- expected trace: active context present and bounded to the intended target;
- expected outcome: mutation or approval flow targets the proposed file;
- no-workspace prompt with prior active context shows suppression.

Verification commands for implementation:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat e2eTest --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
.\gradlew.bat check --no-daemon
```

## Future Design Path

T59 should leave named extension points instead of trying to solve every context
problem now.

Future tickets should cover:

- `ContextPressurePolicy`: token/turn pressure thresholds and warning states;
- `/context` or equivalent user-facing inspection and clear command;
- explicit UX for "continue anyway", "clear context", "compact/summarize", and
  "save handoff summary";
- compaction that preserves active context outside lossy transcript summaries;
- optional retrieval/vector memory only for large fuzzy document or project
  knowledge, never for mutation authorization;
- richer capability-owned `ArtifactGoal` details after T60/T62 capability
  profile work.

The future context-pressure UX should respect the same principle as T59: do not
cut off the user's task. Warn and offer options before quality degrades, but do
not silently end work.

## Acceptance Checklist

T59 is complete when:

- proposal followed by `make those changes` carries target and proposal summary
  into the new turn plan;
- follow-up after static verification failure can use previous verifier
  findings without broad workspace guessing;
- follow-up after approval denial knows no files changed;
- no-workspace chat suppresses active task context;
- unrelated explicit requests do not inherit stale context;
- prompt audit and `/last trace` show active context presence, suppression,
  expiration, or absence;
- tests and TalosBench validation pass.

## Spec Self-Review

Placeholder scan: no unresolved placeholder fields are present.

Internal consistency: the design keeps T59 as small runtime-owned state and does
not merge it with context pressure, compaction UX, vector memory, or capability
profiles.

Scope check: this is a single implementation plan sized for one ticket. Future
context pressure and compaction work are intentionally named but out of scope.

Ambiguity check: "small context" is quantified through one active task, 3-turn
expiration, 5-target cap, 600-character proposal cap, 5-finding cap, and about a
250-token prompt render cap. Current user intent always overrides active
context.
