# Runtime Policy Ownership Map

Date: 2026-04-28
Status: post-0.9.6 planning map
Parent architecture: `docs/architecture/01-execution-discipline-and-local-trust.md`

## Purpose

This map records where runtime policy decisions live today and where they
should move during staged extraction. It is not an implementation plan for a
large rewrite. The goal is to prevent policy extraction from turning into a
package move that preserves the same coupling under new names.

Policy here means deterministic control logic that decides what Talos may do,
what tools the model can see, what outputs are truthful, what evidence is
recorded, and how failures are bounded.

## Current Policy Owners

### `AssistantTurnExecutor`

Current responsibilities:

- Resolves or receives the active `TaskContract` and initializes phase state.
- Selects native tool surface through `NativeToolSpecPolicy`.
- Owns small-talk and capability direct-answer markers.
- Blocks model-emitted tools for small-talk/privacy turns.
- Shapes no-tool, tool-loop, streaming, and retry answers.
- Injects task-contract and static-verification repair instructions.
- Performs read-only inspection retry and mutation retry orchestration.
- Renders verified follow-up summaries from prior assistant text.
- Cleans protocol leakage and fake approval prose after blocked or malformed
  tool output.
- Annotates false mutation claims, partial mutation outcomes, denied mutation
  outcomes, read-only denied mutation outcomes, and invalid mutation outcomes.
- Applies unsupported-document, selector-mismatch, read-only web-diagnostic,
  inspect-under-completion, and local-access claim corrections.
- Records compact policy trace.

Future policy assignments:

- `SmallTalkPrivacyPolicy`: small-talk/capability/privacy direct-answer
  decisions and no-tool enforcement for conversational turns.
- `ToolSurfacePolicy`: native/prompt-visible tool surface selection and
  read-only prompt mode decisions.
- `ProtocolSanitizationPolicy`: protocol leak, malformed protocol, fake
  approval, and blocked-tool prose cleanup.
- `OutcomePolicy`: final answer shaping, false-claim correction, partial
  mutation summaries, and deterministic status follow-up summaries.
- `VerificationPolicy`: when to run static verification and how to incorporate
  verification status into answer shaping.
- `RepairPolicy`: mutation retry, read-only inspection retry, and
  verifier-context repair prompts.
- `TracePolicy`: turn trace assembly and redacted trace output.

Future output objects:

- `PrivacyBoundaryDecision`
- `ToolSurfaceDecision`
- `ProtocolSanitizationResult`
- `OutcomeRenderResult`
- `VerificationDecision`
- `RepairDecision` / `RepairPlan`
- `TurnTraceRecord`

### `TaskContractResolver`

Current responsibilities:

- Classifies the user turn into `TaskType`.
- Determines mutation requested/allowed and verification required.
- Extracts expected and forbidden target paths.
- Handles small-talk, assistant identity, capability, privacy-negated chat,
  workspace-explain, diagnose, verify, create, edit, and repair follow-up
  intent.
- Inherits repair or read-only workspace context from conversation history.
- Applies precedence for prior-change status questions and read-only negations.

Future policy assignments:

- `TaskIntentPolicy`: intent classification, target extraction, repair/status
  inheritance, and mutation/read-only precedence.
- `SmallTalkPrivacyPolicy`: privacy negation and chat-only classification.

Future output objects:

- `TaskIntentDecision`, later converted to `TaskContract`.
- `PrivacyBoundaryDecision`, when a prompt must not inspect workspace data.

### `MutationIntent`

Current responsibilities:

- Detects explicit mutation requests from deterministic lexical markers.
- Detects prior-change status questions.
- Detects global read-only negations.
- Preserves scoped mutation limiters such as "edit only X; do not touch Y".
- Distinguishes artifact-making prompts from instructional "how to make"
  prompts.

Future policy assignments:

- `TaskIntentPolicy`: mutation intent and prior-change status predicates.

Future output object:

- `MutationIntentDecision`, embedded in `TaskIntentDecision`.

### `WebDiagnosticIntent`

Current responsibilities:

- Detects read-only web diagnostic prompts that should inspect HTML/CSS/JS
  without mutation.

Future policy assignments:

- `TaskIntentPolicy`: read-only web diagnostic classification.
- `VerificationPolicy`: static web diagnostic requirements.

Future output object:

- `DiagnosticIntentDecision`.

### `ScopeGuard`

Current responsibilities:

- Identifies web-scoped requests.
- Warns when a mutating target appears off-scope for a web task.
- Keeps the current behavior advisory rather than blocking.

Future policy assignments:

- `ResourcePolicy`: target/resource risk classification.
- `PermissionPolicy`: later escalation from warning to ask/deny when permission
  rules require it.

Future output object:

- `ResourceDecision` with severity `ALLOW`, `WARN`, `ASK`, or `DENY`.

### `StaticTaskVerifier`

Current responsibilities:

- Verifies expected targets and mutated targets.
- Distinguishes readback-only verification from task-specific verification.
- Checks small web workspaces for linked assets, duplicate assets, placeholders,
  selector/id coherence, form/calculator structure, and missing primary web
  files.
- Produces static diagnostics for read-only web inspection.
- Normalizes expected target path matching, including Windows case behavior.

Future policy assignments:

- `VerificationPolicy`: what verifier applies, what evidence is required, and
  whether verification status can support completion.

Future output object:

- `VerificationDecision` and `TaskVerificationResult`.

### `SystemPromptBuilder`

Current responsibilities:

- Builds the system prompt for ask/rag/unified modes.
- Injects tool preambles and descriptor text.
- Applies read-only prompt mode by filtering tool descriptors.
- Adds workspace manifest and retrieval context.

Future policy assignments:

- `ToolSurfacePolicy`: prompt-visible tool descriptors and read-only tool mode.
- `SmallTalkPrivacyPolicy`: no-workspace prompt surface for chat/privacy turns.

Future output object:

- `PromptSurfaceDecision`, containing prompt tool descriptors and workspace
  context visibility.

### `ToolCallLoop`

Current responsibilities:

- Runs the parse/execute/reprompt loop with iteration caps.
- Carries loop outcomes, tool outcomes, and fallback answer text.
- Stops on malformed, unfinished, denied, failed, or capped loops.
- Coordinates parse, execution, and reprompt stages.

Future policy assignments:

- `RepairPolicy`: retry limits, no-progress handling, and bounded repair
  attempts.
- `ProtocolSanitizationPolicy`: protocol parse failures and malformed protocol
  outcomes.
- `TracePolicy`: attempted tool calls and loop stop reasons.

Future output objects:

- `ToolLoopDecision`
- `RepairDecision`
- `ProtocolFailure`
- `TraceToolEvent`

### `ExecutionOutcome`

Current responsibilities:

- Converts no-tool and tool-loop results into completion, grounding, and
  verification status.
- Runs post-apply static verification.
- Builds truth warnings and verification annotations.
- Calls answer-shaping helpers in `AssistantTurnExecutor`.
- Differentiates static verification passed, failed, partial, unavailable, and
  readback-only cases.

Future policy assignments:

- `OutcomePolicy`: central completion/truth classification and final answer
  rendering inputs.
- `VerificationPolicy`: verification status mapping and verification evidence.
- `ProtocolSanitizationPolicy`: protocol-related warnings that must affect
  visible output.

Future output object:

- `ExecutionOutcome` can remain the data carrier, with policy producing an
  `OutcomeRenderResult`.

### `TurnProcessor`

Current responsibilities:

- Central tool execution gateway.
- Enforces task-contract mutation permission.
- Applies phase policy.
- Applies scope guard warnings.
- Applies sandbox/path checks and path parameter validation.
- Applies approval policy and user approval gate for mutating tools.
- Blocks forbidden target mutations.
- Executes registered tools and captures exceptions as tool failures.
- Records audit capture events for tools, approvals, and blocks.

Future policy assignments:

- `PermissionPolicy`: allow/ask/deny decisions, protected paths, and approval
  requirements.
- `ResourcePolicy`: workspace/path target classification.
- `TracePolicy`: structured enforcement events.

Future output object:

- `PermissionDecision`
- `ResourceDecision`
- `TracePolicyBlockEvent`
- `TraceApprovalEvent`

### `ApprovalPolicy`

Current responsibilities:

- Session-level approval state.
- `ALLOW_ONCE`, `ALLOW_SESSION`, and `DENY` decisions.
- Default always-ask behavior.

Future policy assignments:

- `PermissionPolicy`: approval memory and default ask behavior.

Future output object:

- `PermissionDecision` with an approval strategy.

### `NativeToolSpecPolicy`

Current responsibilities:

- Selects native tool specs from the current `TaskContract` and
  `ExecutionPhase`.
- Hides all tools for `SMALL_TALK`.
- Exposes read-only tools in inspect/verify contexts.
- Exposes mutating tools only when mutation is allowed and phase is `APPLY`.

Future policy assignments:

- `ToolSurfacePolicy`: native tool visibility.
- `SmallTalkPrivacyPolicy`: no-tool surface for chat/privacy turns.

Future output object:

- `ToolSurfaceDecision`, including visible native tools, prompt tools, and
  blocked-tool rationale.

## Target Policy Classes

### `TaskIntentPolicy`

Purpose: turn user text and bounded history into a task-intent decision.

Current sources:

- `TaskContractResolver`
- `MutationIntent`
- `WebDiagnosticIntent`
- selected direct-answer markers in `AssistantTurnExecutor`

Future output:

- `TaskIntentDecision`, converted into `TaskContract`.

### `SmallTalkPrivacyPolicy`

Purpose: enforce the boundary between chat/identity/capability prompts and
workspace inspection.

Current sources:

- `TaskContractResolver`
- `NativeToolSpecPolicy`
- `SystemPromptBuilder`
- `AssistantTurnExecutor`

Future output:

- `PrivacyBoundaryDecision` with no-tool/no-workspace instructions.

### `ToolSurfacePolicy`

Purpose: decide native tools, prompt-visible tools, and workspace-context
visibility from task, phase, and privacy decisions.

Current sources:

- `NativeToolSpecPolicy`
- `SystemPromptBuilder`
- `UnifiedAssistantMode`
- `AssistantTurnExecutor`

Future output:

- `ToolSurfaceDecision`.

### `ResourcePolicy`

Purpose: classify resources and paths before permission or verification policy
acts on them.

Current sources:

- `ScopeGuard`
- `TurnProcessor` path and sandbox checks
- `StaticTaskVerifier` expected-target normalization

Future output:

- `ResourceDecision`.

### `PermissionPolicy`

Purpose: produce deterministic allow/ask/deny decisions for tool/resource/phase
combinations.

Current sources:

- `ApprovalPolicy`
- `ApprovalGate`
- `TurnProcessor`
- `PhasePolicy`

Future output:

- `PermissionDecision`.

### `ProtocolSanitizationPolicy`

Purpose: handle model-emitted protocol text that was executed, blocked, denied,
malformed, or should be hidden from final prose.

Current sources:

- `ToolCallParser`
- `ToolCallStreamFilter`
- `ToolCallLoop`
- `AssistantTurnExecutor`
- `ExecutionOutcome`

Future output:

- `ProtocolSanitizationResult`.

### `VerificationPolicy`

Purpose: decide when verification is required, which verifier applies, and what
completion status the evidence can support.

Current sources:

- `StaticTaskVerifier`
- `ExecutionOutcome`
- `AssistantTurnExecutor`
- `WebDiagnosticIntent`

Future output:

- `VerificationDecision` and `TaskVerificationResult`.

### `RepairPolicy`

Purpose: bound repair after verification failure, invalid edit loops, or
incomplete mutation outcomes.

Current sources:

- `StaticVerificationRepairContext`
- `ToolCallLoop`
- `ToolCallRepromptStage`
- `AssistantTurnExecutor`
- `ExecutionOutcome`

Future output:

- `RepairPlan` and `RepairDecision`.

### `OutcomePolicy`

Purpose: render truthful user-visible outcomes from structured execution,
verification, permission, and protocol data.

Current sources:

- `ExecutionOutcome`
- `AssistantTurnExecutor`

Future output:

- `OutcomeRenderResult`.

### `TracePolicy`

Purpose: produce a first-class local trace record with default redaction.

Current sources:

- `TurnPolicyTrace`
- `TurnAuditCapture`
- `AssistantTurnExecutor.recordPolicyTrace`
- `TurnProcessor` audit recording

Future output:

- `TurnTraceRecord`.

### `CheckpointPolicy`

Purpose: decide whether a mutation turn needs a checkpoint and how checkpoint
failure affects execution.

Current sources:

- No production implementation yet.
- Future design tickets T36/T37 define this layer.

Future output:

- `CheckpointDecision` and checkpoint id attached to trace.

## Extraction Order

This is the recommended policy extraction order after the design tickets:

1. `ProtocolSanitizationPolicy`
2. `OutcomePolicy`
3. `SmallTalkPrivacyPolicy`
4. `TaskIntentPolicy`
5. `ToolSurfacePolicy`
6. `TracePolicy`
7. `PermissionPolicy`
8. `CheckpointPolicy`
9. `RepairPolicy`
10. `VerificationPolicy` refinements

`VerificationPolicy` already has the strongest standalone implementation in
`StaticTaskVerifier`, so it should not be moved first. The highest return is
to reduce protocol/outcome/small-talk coupling in `AssistantTurnExecutor`
without changing mutation authority.

## Safest First Extraction

The safest first extraction is `ProtocolSanitizationPolicy`.

Why:

- It is deterministic string/protocol handling, not a permission decision.
- It does not expand tool access or weaken approval.
- It already has recent focused regression coverage from T13, T24, and T27.
- It removes a clear cluster from `AssistantTurnExecutor`: malformed protocol
  replacement, blocked read-only protocol cleanup, fake approval prose removal,
  and protocol-text visibility decisions.
- It can be introduced as a pure helper with no behavior change, then wired
  into outcome rendering.

Required behavior-preserving tests before and after extraction:

- `src/test/java/dev/talos/runtime/ToolCallParserTest.java`
- `src/test/java/dev/talos/runtime/ToolCallStreamFilterTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/47-fenced-write-json-with-backticks-executes.json`
- `src/e2eTest/resources/scenarios/60-malformed-toolcall-json-like-output-no-leak.json`
- `src/e2eTest/resources/scenarios/61-blocked-readonly-tool-json-no-leak.json`

Success condition:

- Parsed valid tool calls still execute.
- Malformed protocol does not leak or stall.
- Read-only denied mutating protocol does not leak fake approval text.
- No final answer claims mutation success without executed mutation evidence.

## Behavior-Preserving Test Matrix

### Intent and privacy

- `MutationIntentTest`
- `TaskContractResolverTest`
- `UnifiedAssistantModeTest`
- Scenarios 24, 37, 41, 45, 49, 56, 57, 58, 59

Policies covered:

- `TaskIntentPolicy`
- `SmallTalkPrivacyPolicy`
- `ToolSurfacePolicy`

### Tool surface and phase

- `NativeToolSpecPolicyTest`
- `AssistantTurnExecutorPhasePolicyTest`
- `TurnProcessorPhasePolicyTest`
- Scenarios 15, 16, 22, 26, 48, 54, 55

Policies covered:

- `ToolSurfacePolicy`
- `PermissionPolicy`
- `ResourcePolicy`

### Approval, sandbox, and resources

- `ApprovalGateTest`
- `ApprovalGatedToolTest`
- `SessionApprovalPolicyTest`
- `TurnProcessorTest`
- `TurnProcessorScopeGuardTest`
- `TurnProcessorPlaceholderGuardTest`
- Scenarios 03, 05, 06, 14, 28, 46

Policies covered:

- `PermissionPolicy`
- `ResourcePolicy`
- `TracePolicy`

### Protocol handling

- `ToolCallParserTest`
- `ToolCallParserLenientJsonTest`
- `ToolCallStreamFilterTest`
- `ToolCallLoopTest`
- `AssistantTurnExecutorTest`
- Scenarios 21, 34, 47, 60, 61

Policies covered:

- `ProtocolSanitizationPolicy`
- `RepairPolicy`
- `OutcomePolicy`

### Verification and repair

- `StaticTaskVerifierTest`
- `ExecutionOutcomeTest`
- `AssistantTurnExecutorTest`
- Scenarios 17, 18, 19, 23, 27, 29, 30, 44, 50, 51, 52, 53, 62, 63

Policies covered:

- `VerificationPolicy`
- `RepairPolicy`
- `OutcomePolicy`

### Trace and evidence

- `TurnTraceCaptureTest`
- Existing e2e harness scenario assertions
- Future T32/T33 trace schema tests

Policies covered:

- `TracePolicy`

## Non-Goals For Extraction

- Do not add shell, browser, MCP, A2A, or multi-agent capabilities as part of
  policy extraction.
- Do not replace deterministic safety decisions with an LLM classifier.
- Do not move phrase lists into an untyped YAML dump.
- Do not weaken `TurnProcessor` as the enforcement gateway.
- Do not make `ApprovalGate` bypassable by prompt or model output.
- Do not make checkpoint/restore implicit before T36/T37 design and
  implementation tickets.

## Review Checklist For Future Extraction Tickets

Before extracting any policy:

- Identify the current owner methods.
- Add or confirm focused unit tests on current behavior.
- Add or confirm one deterministic e2e scenario when user-visible behavior can
  change.
- Extract pure decision logic first.
- Keep enforcement in the existing gateway until the new policy object is
  tested.
- Run the documented work-test cycle for the ticket.
- Do not declare completion if only call sites moved but behavior changed
  without explicit acceptance criteria.
