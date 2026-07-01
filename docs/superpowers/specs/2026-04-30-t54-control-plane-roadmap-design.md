# T54 Control Plane Roadmap Design

Date: 2026-04-30

Status: design approved for ticket sequencing

Source milestone: T54 prompt audit re-evaluation

## Goal

Turn the T54 audit findings into a release-blocking control-plane roadmap for
Talos before writing implementation plans. The roadmap should make Talos rely on
runtime-owned turn facts, obligations, permissions, verification, and outcome
dominance instead of asking the local model to infer those responsibilities from
prompt prose.

## User-Approved Decomposition

The approved sequence is:

1. T55: `CurrentTurnPlan`
2. T56: `ConversationBoundaryPolicy` and `READ_ONLY_QA` shrink
3. T57: `EvidenceObligationPolicy`
4. T58: `OutcomeDominancePolicy`
5. T61: T54 TalosBench regression pack, interleaved early
6. T59: `ActiveTaskContext` and `ArtifactGoal`
7. T60: `ToolAliasPolicy` and `BackendToolProfile`
8. T62/T47: capability profile spine, then static web repair follow-through
9. Candidate gate: resume 0.9.8 release review only after T54 blockers become
   passing assertions or are explicitly scoped out.

This design intentionally keeps the work split across separate tickets. T55
through T58 form the release-blocker control loop. T59 through T62 are follow-up
architecture that should not block the first obligation/outcome hardening pass
unless implementation proves the split unsafe.

## Source Index

Local sources:

- `local/manual-workspaces/t54-audit-20260430-105839/t54-re-evaluation-report.md`
- `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- `docs/architecture/07-domain-specificity-and-extensibility-audit.md`
- `work-cycle-docs/tickets/done/[T54-done-high] prompt-audit-and-current-turn-plan-visibility.md`
- `work-cycle-docs/tickets/open/[T47-open-medium] improve-cross-file-web-repair-coherence-after-full-write.md`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/policy/ActionObligationPolicy.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
- `tools/manual-eval/talosbench-cases.json`

External references:

- OpenAI Agents SDK guardrails: https://openai.github.io/openai-agents-python/guardrails/
- OpenAI Agents SDK tracing: https://openai.github.io/openai-agents-python/tracing/
- OpenAI Codex approvals and security: https://developers.openai.com/codex/agent-approvals-security
- local coding assistant permissions: https://code.external assistant.com/docs/en/permissions
- local coding assistant settings: https://code.external assistant.com/docs/en/settings
- Gemini CLI filesystem tools: https://google-gemini.github.io/gemini-cli/docs/tools/file-system.html
- Gemini CLI checkpointing: https://google-gemini.github.io/gemini-cli/docs/cli/checkpointing.html
- Terminal-Bench benchmarks: https://www.tbench.ai/benchmarks

## Problem Statement

T54 proved that Talos now has enough prompt audit visibility to diagnose current
turn failures, but the runtime still lacks the control-plane invariants needed
for a reliable local assistant.

The failures are not one prompt family. They cluster around:

- casual chat being classified as `READ_ONLY_QA` and exposing read/search tools;
- natural artifact creation falling through to read-only behavior;
- explicit file reads answering without fresh file evidence;
- protected reads requiring approval only if the model chooses to call a read
  tool;
- failed action obligations rendering as completed read-only answers;
- retry paths mutating `messages` and causing later contract or expectation
  derivation to drift;
- follow-ups like "make those changes" relying on chat reconstruction instead
  of structured active task state;
- backend-specific tool-call aliases living in generic support code.

The design response is to move from prompt-centered control to typed runtime
state and policy boundaries.

## Design Principles

- Runtime policy owns obligations. The local model can decide wording and use
  available tools, but it must not own whether the turn requires inspection,
  mutation, verification, or permission.
- Prompt frames reinforce runtime state; they are not the source of truth.
- Tool surface should be minimized per turn. Data minimization includes not
  exposing read/search tools to ordinary conversation.
- Evidence is a first-class obligation. "Read file X" must lead to a read,
  approval denial, unsupported capability statement, or incomplete outcome.
- Outcome truth must be dominated by the strongest unmet obligation.
- Keep near-term capabilities static and typed. Do not add shell, browser, MCP,
  dynamic plugins, or multi-agent orchestration to solve T54.
- Reputable agent architectures separate input, output, and tool guardrails;
  Talos should adapt that separation locally through deterministic policies.

## Architecture

### CurrentTurnPlan

`CurrentTurnPlan` is an immutable record created once near the start of a user
turn. It must survive retries, synthetic messages, tool results, and final
outcome rendering.

It should initially contain:

- original user request;
- resolved task contract or replacement intent model;
- execution phase;
- action obligation;
- evidence obligation;
- output obligation;
- visible native and prompt tool surfaces;
- expected and forbidden targets;
- literal expectations;
- protected resource intent;
- verifier profile name;
- artifact goal summary;
- active task context summary;
- prompt audit id, hash, or summary fields.

It should not become a planner. It should be a typed, immutable bundle of facts
that existing policies can consume without re-reading `messages`.

### Intent And Conversation Boundaries

`READ_ONLY_QA` currently absorbs too many incompatible meanings. T56 should
introduce deterministic boundaries before the runtime exposes workspace tools.

The first pass should distinguish:

- conversational greeting;
- acknowledgement or closure;
- capability or product identity chat;
- privacy/no-workspace chat;
- slash-command typo or near-command phrase;
- directory listing;
- explicit file read;
- protected file read intent;
- workspace explanation;
- artifact create/edit intent;
- unsupported capability request;
- residual read-only Q&A.

The near-term implementation can keep `TaskType` if needed, but the design
direction is a narrower intent policy with explicit obligations.

### EvidenceObligationPolicy

Evidence obligations should answer: what evidence must exist before the final
answer can be trusted?

Examples:

- `Read README.md` requires a successful `talos.read_file` on `README.md` or a
  clear failure.
- `Read .env` requires protected read approval flow before content can be used.
- `List files here, but do not read contents` requires `talos.list_dir` only.
- `Can you read report.docx and summarize it?` requires checking existence and
  reporting unsupported format if the current tool surface cannot extract it.
- `What did you change?` should use previous verified outcome or trace state,
  not model memory alone.

The policy should produce a typed obligation that can be shown in prompt audit,
used by tool-surface selection, and enforced by outcome dominance.

### OutcomeDominancePolicy

Outcome rendering should be centralized around precedence rules:

- protected resource denial beats prose;
- failed mutating obligation beats prose;
- failed evidence obligation beats prose;
- exact expectation failure beats write/readback success;
- verifier failure beats completion claims;
- malformed protocol failure beats model narrative;
- partial mutation remains partial even if the answer sounds complete.

This policy should reduce ad hoc answer-shaping spread across
`AssistantTurnExecutor` and `ExecutionOutcome`.

### ActiveTaskContext And ArtifactGoal

After the release-blocker loop, Talos needs structured follow-up state for
ongoing work:

- active targets;
- proposed operation;
- artifact kind and operation;
- latest verified file state or hash when known;
- previous verifier findings;
- previous denied or blocked outcome;
- previous proposed edit text when the user says "make those changes".

This should be conservative. Active context can help deictic follow-ups, but it
must not override a clear new user request or privacy/no-workspace turn.

### ToolAliasPolicy And BackendToolProfile

Provider and model tool dialects should be profile-owned. Known aliases such as
Talos prefixes or selected backend spellings can be normalized, but unknown
names should fail cleanly and traceably.

The policy should:

- map only explicit aliases;
- record normalized and rejected aliases in trace;
- preserve read-only versus mutating risk classification;
- avoid broad namespace acceptance.

### Capability Profile Spine And T47

T47 remains real, but it is not the next control-plane step. Static web repair
should move behind a capability/profile boundary after the turn plan,
obligation, outcome, and regression gates are stable.

The minimal later spine should include:

- static Java capability registry;
- artifact kind and operation;
- target extraction ownership;
- verifier profile selection;
- repair profile selection;
- profile-owned prompt guidance;
- profile-owned TalosBench cases.

No dynamic marketplace or plugin loader is required for this milestone.

## Data Flow

The intended turn flow is:

1. Receive original user request.
2. Build immutable `CurrentTurnPlan`.
3. Select phase, tool surface, action obligation, evidence obligation, and
   output obligation from the plan.
4. Render current-turn frame and prompt audit from the plan.
5. Execute model and tools.
6. Validate tool outcomes against action and evidence obligations.
7. Run static or expectation verification when the plan requires it.
8. Apply `OutcomeDominancePolicy`.
9. Persist trace, prompt audit summary, outcome, and active task context update.

No post-model step should re-derive the turn contract from mutated `messages`.

## Error Handling

Expected failures should become explicit outcomes:

- `BLOCKED_BY_APPROVAL` for user-denied protected read or mutation approval;
- `BLOCKED_BY_POLICY` for read-only turns that attempt mutation;
- `FAILED` for invalid tool arguments, malformed protocol debris, exact
  expectation failure, or unfulfilled required action;
- `PARTIAL` for mixed mutation success/failure;
- `ADVISORY_ONLY` for read-only answers that are useful but not evidence
  grounded;
- `UNSUPPORTED_CAPABILITY` when the requested file type or operation is outside
  current Talos capability.

These statuses should appear in `/last trace` and TalosBench assertions.

## Evaluation Strategy

T61 should not wait until the end. As each policy lands, add deterministic unit
tests and TalosBench cases from T54.

Required prompt families:

- `Hello friend`
- `how are you are you good?`
- `perfect just as I want it!`
- `debug /trace`
- natural artifact creation: `I want to make a webpage... Can you create it here?`
- `List the files here, but do not read their contents.`
- `Read config.json...`
- `Read .env...` with deny and approve variants;
- propose README changes, then `make those changes`;
- exact literal README write after mutating-obligation retry;
- `Can you read report.docx and summarize it?`
- model-switch small talk;
- unknown tool alias replay from earlier freestyle output.

Release-review should use a combination of:

- focused unit tests for policies and outcome dominance;
- executor/integration tests for plan immutability and retries;
- e2e or TalosBench runs for live local-model behavior;
- prompt audit assertions for tool surface and obligation fields.

## Ticket Sequence

### T55: CurrentTurnPlan

Foundation. Creates immutable turn state and makes prompt audit consume it.

Exit criteria:

- retry messages do not change contract, obligation, target, or expectation;
- exact literal write expectation survives mutating-obligation retry;
- `ExecutionOutcome` no longer re-derives core turn facts from `messages`;
- prompt audit renders plan fields.

### T56: ConversationBoundaryPolicy And READ_ONLY_QA Shrink

Privacy and data-minimization blocker.

Exit criteria:

- casual chat has no tools;
- acknowledgements have no tools;
- capability chat remains deterministic;
- command-like typos do not fall into workspace QA;
- real workspace prompts still expose appropriate read-only tools.

### T57: EvidenceObligationPolicy

Read/evidence blocker.

Exit criteria:

- explicit file reads require evidence;
- protected reads enter approval flow;
- unsupported document requests are truthful and evidence-grounded;
- list-only remains list-only;
- zero-tool evidence answers cannot complete as ordinary success.

### T58: OutcomeDominancePolicy

Truthfulness blocker.

Exit criteria:

- unmet action and evidence obligations dominate answer text;
- exact expectation failure dominates readback success;
- protected read denial cannot leak or complete;
- trace and final task outcome agree.

### T61: TalosBench T54 Regression Pack

Evaluation gate, interleaved with T56 through T58.

Exit criteria:

- every T54 blocker has at least one regression case;
- trace assertions cover contract, obligation, tools, outcome, and redaction;
- approval-sensitive cases are marked manual or scripted explicitly;
- failures produce actionable summary rows.

### T59: ActiveTaskContext And ArtifactGoal

Follow-up coherence.

Exit criteria:

- proposed changes can be applied by follow-up without broad workspace guessing;
- prior denial, partial, and verification failure state is available;
- context is cleared or suppressed for unrelated and no-workspace turns.

### T60: ToolAliasPolicy And BackendToolProfile

Backend protocol hardening.

Exit criteria:

- known aliases are normalized with trace evidence;
- unknown aliases fail cleanly;
- mutating/read-only risk is preserved after normalization;
- backend examples do not leak into generic policy.

### T62: Minimal Capability Profile Spine And T47 Sequencing

Capability ownership follow-up.

Exit criteria:

- static web verifier/repair guidance has a profile owner;
- generic turn control stops owning web-specific repair details;
- T47 can proceed as a static web profile refinement.

## Release Gate

0.9.8 release review should stay paused until these are true or deliberately
scoped out in release notes:

- ordinary conversation exposes no workspace tools;
- natural artifact creation is mutation-capable under approval;
- explicit read requests are evidence-bound;
- protected read requests enter approval and cannot leak on denial;
- failed mutating and evidence obligations cannot render as complete;
- exact literal verification survives retry paths;
- T54 regression cases are represented in TalosBench or deterministic tests.

## Non-Goals

- No shell/test-runner/browser/MCP expansion.
- No dynamic plugin marketplace.
- No multi-agent handoff architecture.
- No LLM classifier for safety-critical policy.
- No one-off phrase patching as the primary fix.
- No raw private transcripts committed to the repository.
- No version bump or changelog update until a candidate closeout ticket.

## Spec Self-Review

Placeholder scan: no unresolved placeholder fields are present.

Internal consistency: the ticket sequence matches the approved decomposition and
keeps T55 through T58 as release-blocking control-plane work.

Scope check: this design intentionally decomposes the work into separate ticket
plans. A single implementation plan for all tickets would be too large and
would mix independent policy boundaries.

Ambiguity check: T61 is listed after T58 by ticket number, but it should be
implemented incrementally as T56 through T58 land. T47 is preserved as open work
but sequenced after the minimal capability profile spine.
