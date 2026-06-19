# Execution Discipline And Local Trust Infrastructure

This is the canonical post-0.9.6 architecture spine for Talos.

Talos is not a swarm, a theatrical multi-agent system, a browser automation
toy, a shell automation layer, an MCP marketplace, a cloud-first product, or a
background autonomous daemon. Talos is a local-first Java workspace assistant
built around execution discipline: it inspects before acting, retrieves before
guessing, asks before writing, verifies before claiming completion, and
preserves evidence after the turn.

## 1. Status After 0.9.6

The Trust and Policy Boundary Stabilization batch is closed.

Verified evidence for candidate 0.9.6:

- tickets T11-T28 are done
- `./gradlew.bat check --no-daemon` passed before candidate declaration
- `./gradlew.bat e2eTest --no-daemon` passed before candidate declaration
- post-candidate and post-merge `check` and `e2eTest` passed
- `e2e-summary.json` reported 83/83 e2e tests passing
- the deterministic scenario pack contains 64 JSON scenarios
- installed Talos manual smoke testing passed privacy, mutation, and status
  boundaries
- fresh native Qodana SARIF evidence exists for `v0.9.0-beta-dev` at merge
  commit `2a00e1a`, with 4 high findings and 0 critical findings

Talos now has real foundations:

- `TaskContract` and `TaskContractResolver`
- `ExecutionPhase` and `PhasePolicy`
- `ToolCallLoop`
- `TurnProcessor` as the central tool execution gateway
- `ApprovalGate` and `ApprovalPolicy`
- `TurnAuditCapture` and compact `TurnPolicyTrace`
- `StaticTaskVerifier`
- centralized execution outcome shaping
- deterministic scenario coverage for trust and policy boundaries

What remains weak:

- policy ownership is still spread across several classes
- `AssistantTurnExecutor` still owns too many policy, copy, retry,
  verification, and sanitization responsibilities
- `TaskContractResolver` still holds too many lexical policy markers
- `TurnPolicyTrace` is compact and useful, but is not yet a first-class local
  trace model
- `ApprovalPolicy` is session-scoped and is not yet declarative allow/ask/deny
- checkpoint/restore is not yet a real trust layer
- repair control exists as behavior, but not yet as a dedicated `RepairPolicy`
- Qodana has 4 known high findings that should be cleaned up, but they are not
  milestone blockers

## 2. Architecture Principle

Talos is a local-first Java workspace assistant built around execution
discipline: it inspects before acting, retrieves before guessing, asks before
writing, verifies before claiming completion, and preserves evidence after the
turn.

The central quality target is not model hype. The central quality target is a
trustworthy local execution harness around an imperfect local model.

## 2A. Current Trust-Surface Limits

Talos should be described as an auditable local operator that refuses to trust
model capability without runtime evidence. Current implementation limits must
stay visible in product, architecture, and audit docs.

Talos's deterministic no-change/no-success correction is strongest for file-mutation turns; `run_command` claims and read/answer factual claims are not yet equivalently covered.

Secret redaction currently catches common key=value secret shapes and known canaries; it does not yet detect standalone API tokens, JWTs, PEM private-key blocks, connection strings, or high-entropy blobs.

`run_command` stdout and stderr are not withheld from model context by default.

On Windows, paths that differ only by trailing dots or spaces can bypass exact-name protected-path matching.

Chat model endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend; when remote chat is explicitly allowed, full prompts can leave this machine.

The local master key is still stored beside the encrypted data, so current encryption is casual-inspection protection, not OS-backed key custody.

Local traces and logs are durable evidence artifacts, but they are not tamper-evident.

## 3. Control Loop

The intended control loop is:

```text
User request
-> TaskContract
-> policy decisions
-> tool surface
-> permission/resource decision
-> checkpoint if mutation
-> tool execution
-> verification
-> repair decision if needed
-> truthful outcome
-> local trace
-> scenario/evidence feedback
```

Each step should become inspectable, deterministic where safety matters, and
covered by unit tests or JSON-backed scenarios.

## 4. COSO-Inspired Control Mapping

Talos does not implement COSO, and it should not import compliance bureaucracy
into the product.

COSO is useful only as a control mindset:

- risk assessment -> tool, resource, and task risk classification
- control activities -> allow/ask/deny, sandbox, approval, checkpoint
- information/communication -> trace, explain-last-turn, truthful outcome
- monitoring -> regression scenarios, quality summaries, manual QA corpus
- control environment -> local-first user-controlled doctrine

This mapping should guide discipline and evidence. It should not create roles,
audit-office language, enterprise governance, or ceremony as product
requirements.

## 5. Policy Extraction Target

Future policy code should move toward `dev.talos.runtime.policy`.

This is staged extraction, not a big-bang rewrite. Each extraction should be
behavior-preserving first, then improved behind focused tests and scenarios.

### TaskIntentPolicy

- Purpose: classify user intent into task-relevant policy facts.
- Current responsibility: `TaskContractResolver`, `MutationIntent`,
  `WebDiagnosticIntent`, and some `AssistantTurnExecutor` direct-answer gates.
- Future output object: `TaskIntentDecision`, feeding `TaskContract`.

### SmallTalkPrivacyPolicy

- Purpose: protect casual chat and explicit privacy-negated prompts from
  workspace inspection.
- Current responsibility: `TaskContractResolver`, `NativeToolSpecPolicy`,
  `UnifiedAssistantMode`, and direct answer paths in `AssistantTurnExecutor`.
- Future output object: `PrivacyBoundaryDecision` with no-tool/no-workspace
  requirements.

### ToolSurfacePolicy

- Purpose: decide which tools are visible to the model for a turn.
- Current responsibility: `NativeToolSpecPolicy`, `SystemPromptBuilder`, and
  mode-specific prompt construction in `UnifiedAssistantMode`.
- Future output object: `ToolSurfaceDecision` with native tools, prompt tools,
  and hidden/blocked reasons.

### ResourcePolicy

- Purpose: classify paths/resources before tool execution.
- Current responsibility: workspace sandbox checks, `ScopeGuard`, and pieces
  of `TurnProcessor`.
- Future output object: `ResourceDecision` with normalized path, resource kind,
  workspace status, and protected-path flags.

### PermissionPolicy

- Purpose: produce allow/ask/deny decisions for tool/resource/phase risk.
- Current responsibility: `ApprovalPolicy`, `ApprovalGate`, `TurnProcessor`,
  and phase checks.
- Future output object: `PermissionDecision` with deny-first precedence,
  rationale, and approval presentation data.

### ProtocolSanitizationPolicy

- Purpose: keep model-emitted protocol text from leaking as normal prose.
- Current responsibility: `ToolCallParser`, `ToolCallStreamFilter`,
  `ExecutionOutcome`, and `AssistantTurnExecutor` cleanup methods.
- Future output object: `ProtocolSanitizationResult` with executed, rejected,
  sanitized, or no-protocol status.

### VerificationPolicy

- Purpose: choose what verification applies after a turn and what its result
  means.
- Current responsibility: `StaticTaskVerifier`, `ExecutionOutcome`, and
  verifier-related answer shaping in `AssistantTurnExecutor`.
- Future output object: `VerificationDecision` and `VerificationOutcome`.

### RepairPolicy

- Purpose: bound repair attempts after verification failure or invalid edit
  loops.
- Current responsibility: `StaticVerificationRepairContext`,
  `ToolCallRepromptStage`, `ToolCallLoop`, and retry prompts in
  `AssistantTurnExecutor`.
- Future output object: `RepairPlan` with reread requirements, allowed retry
  count, verifier findings, and stop conditions.

### OutcomePolicy

- Purpose: render truthful final answers from structured outcomes.
- Current responsibility: `ExecutionOutcome` plus many answer-shaping helpers
  in `AssistantTurnExecutor`.
- Future output object: `OutcomeRenderResult` with user text, warnings,
  completion status, and trace summary.

### TracePolicy

- Purpose: decide what trace events are recorded and how they are redacted.
- Current responsibility: `TurnAuditCapture`, `TurnPolicyTrace`, session logs,
  and debug trace output.
- Future output object: `TurnTraceRecord` plus redacted/full capture modes.

### CheckpointPolicy

- Purpose: decide whether and how to snapshot local files before mutation.
- Current responsibility: not implemented as a layer.
- Future output object: `CheckpointDecision` with checkpoint id, included
  paths, storage backend, and fail-closed behavior.

## 6. What AssistantTurnExecutor Should Become

Target responsibility:

- receive or resolve `TaskContract`
- initialize phase
- select tool surface through policy
- call the model
- run `ToolCallLoop`
- call an outcome renderer/policy
- record trace

It should not own:

- all small-talk markers
- all capability markers
- all mutation claim markers
- all protocol leak phrases
- all verification wording
- all retry policy
- all truth annotation copy

`AssistantTurnExecutor` should remain an orchestrator. It should not keep
becoming the policy warehouse.

## 7. Permission Direction

The first permission version should be capability/resource/phase-aware
allow/ask/deny.

It should not be enterprise RBAC.

Deny-first precedence:

- deny beats ask
- ask beats allow
- defaults must be conservative for mutating operations
- read-only tools may auto-allow only inside workspace constraints

Protected paths to consider in the permission ticket:

- `.env`
- `.env.*`
- `**/secrets/**`
- `**/*secret*`
- `**/*token*`
- `**/*credential*`
- private keys
- SSH keys
- cloud credential files

This list is a design subject for the permission ticket, not a final exhaustive
rule set. The implementation must be tested with Windows path normalization and
workspace-boundary checks.

## 8. Trace Direction

Local trace v1 must answer:

- what task contract was resolved?
- what phase was selected?
- what tools were visible?
- what tool calls were attempted?
- what was blocked and why?
- was approval required, granted, or denied?
- what changed?
- what verification ran?
- what outcome was reported?

Privacy posture:

- default trace must avoid storing full sensitive content
- full prompt/tool payload capture should be explicit opt-in debug mode
- trace storage is local-only
- trace records should be deterministic enough for tests and readable enough
  for `/explain-last-turn`

`TurnPolicyTrace` is the current compact trace. It is useful, but it is not the
complete local trace model.

## 9. Checkpoint Direction

Checkpoint/restore is a future trust layer.

Design constraints:

- local only
- Windows-first
- snapshot before approved mutation
- fail closed if checkpointing is enabled and snapshot fails
- JGit/shadow repository is preferred for design, but the implementation ticket
  must verify dependency and storage tradeoffs
- checkpoint id should be attached to trace

The checkpoint layer must arrive before Talos grows more dangerous tool
surfaces such as shell or browser automation.

## 10. Repair Direction

Repair control should follow trace and permission foundations.

Goal:

- bounded repair
- reread before retry
- verifier findings passed into repair
- explicit stop conditions
- no blind edit loop
- no fake completion after failed verification

The current static verification repair context is a useful slice, not the
final repair controller.

## 11. Qodana Handling

Fresh local native Qodana evidence should use:

```powershell
./gradlew.bat qodanaNativeFreshLocal --no-daemon
./gradlew.bat talosQualitySummaries --no-daemon
```

`qodanaNativeLocal` alone may print findings without refreshing the
summary-compatible output path under `.qodana/report/results`.

0.9.6 Qodana evidence is current:

- summary status: `qodana-results-match-current-candidate`
- branch: `v0.9.0-beta-dev`
- revision: `2a00e1a`
- total issues: 4
- high issues: 4
- critical issues: 0
- artifact status: `sarif-only-results-present`

The four high findings are cleanup follow-ups, not roadmap blockers. Future
candidates must not present stale Qodana summaries as clean evidence.

## 12. Do-Not-Do List

Do not add:

- shell execution yet
- browser automation yet
- MCP-first work yet
- A2A or multi-agent orchestration yet
- background daemon or KAIROS-like mode
- LLM classifiers for safety-critical permission, privacy, or mutation
- giant untyped YAML phrase dumps
- LangChain, Spring AI, or framework rewrites

The next milestone is Execution Discipline and Local Trust Infrastructure.
Build the trust layers first, then consider broader capabilities.
