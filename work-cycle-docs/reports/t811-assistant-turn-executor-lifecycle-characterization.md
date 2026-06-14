# T811 AssistantTurnExecutor Lifecycle Ownership Characterization

Evidence identity:

- Branch: `v0.9.0-beta-dev`
- Pre-characterization HEAD: `1e499b2bcab4b43f8ecbc7d060ded9ac98a26628`
- Current generated evidence HEAD: `1e499b2bcab4b43f8ecbc7d060ded9ac98a26628`
- Talos version: `0.10.5`
- Primary sources:
  `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java` and
  `src/main/java/dev/talos/cli/modes/AssistantTurnPreparation.java`
- Active ticket: `work-cycle-docs/tickets/open/[T811-in-progress-high] assistant-turn-executor-lifecycle-ownership-characterization.md`

The T807 priority index is only a review-order heuristic. It ranks
`cli.modes.AssistantTurnExecutor` first because the class combines high call
traffic with turn lifecycle, approval/tool, and trace/privacy signals. It is
not a grade, proof of ownership correctness, or extraction mandate.

This report started as the pre-extraction lifecycle characterization baseline.
It now also records the first behavior-preserving extraction result. It is not
a broad package-move mandate; every later extraction still needs source evidence
and focused behavior coverage.

## Source Anchors

`AssistantTurnExecutor.execute(...)` is still the stable public entrypoint. The
current turn path includes these source anchors:

- public entrypoint: `AssistantTurnExecutor.execute(...)`
- prompt-debug turn start: `PromptDebugCapture.beginTurn()`
- workspace boundary setup remains in `AssistantTurnExecutor` through
  `WorkspaceBoundaryPreflight`
- ordered turn preparation now goes through
  `AssistantTurnPreparation.prepare(...)`
- turn preparation includes `TaskContractResolver.fromMessages(...)`,
  `WorkspaceTargetReconciler.reconcile(...)`,
  `activeTaskContextDecision(...)`, `applyActiveTaskMemoryDecision(...)`,
  `initializeExecutionPhaseForTurn(...)`, `withNativeToolSurface(...)`,
  `buildCurrentTurnPlan(...)`, `recordPolicyTrace(...)`,
  `loadProjectMemory(...)`, `injectProjectMemoryInstruction(...)`,
  `injectTaskContractInstruction(...)`,
  `injectStaticVerificationRepairInstruction(...)`,
  `recordPromptAudit(...)`, and `recordPromptDebugDiagnostics(...)`
- unsupported capability preflight: `unsupportedCapabilityPreflightIfNeeded(...)`
- scoped runtime captures: `TurnSourceEvidenceCapture.begin()` and
  `TurnTaskContractCapture.set(...)`
- tool-loop dispatch: `ctx.toolCallLoop().run(...)`
- tool-loop outcome path: `resolveToolLoopAnswer(...)`
- no-tool outcome path: `resolveNoToolAnswer(...)`
- final shaping: `shapeAnswerAfterToolLoop(...)` and
  `shapeAnswerWithoutTools(...)`
- retry ownership currently inside the class:
  `mutationRequestRetryIfNeeded(...)`, `inspectCompletenessRetryIfNeeded(...)`,
  read-evidence handoff, read-only inspection retry, synthesis retry, and
  grounding retry
- cleanup: `TurnTaskContractCapture.clear()` and
  `TurnSourceEvidenceCapture.clear()`

## Lifecycle Ownership Map

| Scope | Current `AssistantTurnExecutor` relation | Evidence | Wave 5 meaning |
|---|---|---|---|
| `APPLICATION` | Does not own application-scoped resources. The class is a static public turn executor and borrows `Context` resources. | Public utility class, borrowed `Context`, borrowed `LlmClient`, borrowed `ToolCallLoop`. | Do not move application wiring into this class. Application composition remains outside this ticket. |
| `SESSION` | Reads and mutates session-adjacent state through `Context`, active task memory, session memory, debug settings, and tool-loop handles. | `applyActiveTaskMemoryDecision(...)`, `ctx.session()`, `ctx.toolCallLoop()`, `SessionMemory`, `ChangeSummaryContext`. | Later extraction must keep session state borrowed, not copied into turn-owned collaborators. |
| `TURN` | Owns the current turn derivation and setup sequence. The first extraction moved the ordered preparation sequence to `AssistantTurnPreparation` while `AssistantTurnExecutor.execute(...)` remains the stable entrypoint. | `TaskContract`, `CurrentTurnPlan`, execution phase, native tool surface, project memory injection, static repair context injection, prompt audit, deterministic direct answers. | First extraction candidate completed for turn preparation; next extraction should not expand scope beyond the evidence-backed order. |
| `TOOL_LOOP` | Delegates loop execution to `ToolCallLoop`, but owns post-loop resolution and retry orchestration. | `ctx.toolCallLoop().run(...)`, `resolveToolLoopAnswer(...)`, `MissingMutationRetry`, `InspectCompletenessRetry`, read-evidence recovery. | Extract only after tests prove approval/tool semantics and trace outcomes stay unchanged. |
| `TOOL_CALL` | Does not own individual tool calls; it consumes `LoopResult` and tool summaries. | `ToolCallLoop.LoopResult`, `toolsInvoked`, `toolOutcomes`, mutating/read-only success counts. | Do not pull tool-call execution policy into `AssistantTurnExecutor` collaborators. Tool-call ownership remains with runtime/tool loop. |
| `TRACE` | Starts, records, and clears several per-turn trace/prompt-debug captures. | `PromptDebugCapture`, `TurnSourceEvidenceCapture`, `TurnTaskContractCapture`, `LocalTurnTraceCapture`, `TurnAuditCapture`. | Trace ownership is high-risk. Any extraction must preserve begin/set/record/clear ordering. |
| `TEMPORARY` | Owns transient values created during one execute invocation. | `StringBuilder out`, local `answer`, `StreamResult`, `CompletableFuture`, retry-local loop results. | Safe to move only when values remain invocation-local and non-shared. |
| `UNKNOWN` | Several policy/rendering helpers still live here because their final owner is not proven by runtime evidence yet. | answer shaping, no-tool truthfulness, unsupported capability preflight, retry adapters, verification-answer shaping. | Treat as review candidates, not automatic extraction targets. |

## Existing Behavior Coverage

These existing tests are part of the characterization baseline and the first
turn-preparation extraction proof. Later extractions must either keep these
tests green or replace them with narrower tests that prove the same behavior.

| Invariant | Current coverage |
|---|---|
| Turn policy trace records task type, mutation permission, verification requirement, and phase. | `AssistantTurnExecutorTest.recordsPolicyTraceInActiveTurnAudit` |
| Workspace target reconciliation feeds prompt/policy trace. | `AssistantTurnExecutorTest.policyTraceUsesWorkspaceReconciledStaticWebTargets` |
| Direct local answers clear stale provider prompt-debug state. | `AssistantTurnExecutorTest.directTurnClearsStalePromptDebugCapture` |
| Project memory enters eligible workspace prompts but cannot rewrite runtime policy or tool surface. | `AssistantTurnExecutorProjectMemoryTest.executorLoadsWorkspaceProjectMemoryIntoProviderPromptForEligibleWorkspaceTurn`; `AssistantTurnExecutorProjectMemoryTest.hostileProjectMemoryDoesNotAlterRuntimePolicyOrToolSurface` |
| Protected read denial is approval-blocked and secret-safe. | `AssistantTurnExecutorTest.protectedReadDenialKeepsSecretOutAndBlocksOutcome` |
| Streaming protected read no-tool path buffers recovery so approval can run before visible prose. | `AssistantTurnExecutorTest.streamingProtectedReadNoToolAnswerUsesBufferedRecoveryAndApproval` |
| Verification status answers trust latest Talos runtime verifier state over model overclaim. | `AssistantTurnExecutorTest.verificationStatusQuestionUsesLatestRuntimeVerifierFailureNotModelOverclaim` |
| Unsupported document/image/archive final answers are corrected by runtime postconditions. | `UnsupportedFinalAnswerTruthfulnessTest.model_attempted_fabrication_is_overridden_by_runtime_postcondition` |

## Extraction Order Authorized By This Characterization

This report supports the following Wave 5 order and no broader rewrite:

1. Turn preparation and prompt-audit setup - implemented in T811 through
   `AssistantTurnPreparation`.
2. Model dispatch boundary for streaming and non-streaming provider calls.
3. Tool-loop outcome resolution.
4. No-tool outcome resolution.
5. Residual adapter thinning after the two outcome paths are separated.

Final-answer truthfulness is not a standalone owner. It is an invariant inside
tool-loop outcome resolution and no-tool outcome resolution.

## Stop Conditions

Stop Wave 5 extraction and add characterization or regression coverage if any
of these occur:

- an extraction requires changing public CLI behavior or the public
  `AssistantTurnExecutor.execute(...)` signature;
- an extraction touches `SetupCmd.java` or setup/profile branch-conflict
  surface;
- a behavior is only implied by architecture score and not proven by tests or
  source evidence;
- a collaborator would store turn, trace, prompt-debug, or approval state across
  turns without explicit session ownership;
- the refactor makes `check` green while focused `dev.talos.cli.modes.*` tests
  are red or skipped;
- the T807 priority index is used as a hard success/failure metric instead of
  review ordering evidence.

## Current Result

T811 has implemented the first behavior-preserving Wave 5 extraction:
ordered turn preparation and prompt-audit setup moved to
`AssistantTurnPreparation`, while `AssistantTurnExecutor.execute(...)` remains
the public entrypoint and model/tool/outcome paths remain in the executor.

## Post-Extraction Result

Current generated architecture evidence for commit
`1e499b2bcab4b43f8ecbc7d060ded9ac98a26628` ranks
`cli.modes.AssistantTurnExecutor` first with priority index `401`
(`hotspot=290`, `lifecycle=56`, `approvalTool=30`, `tracePrivacy=25`,
`INFERRED_REVIEW`). The previous `466` value was pre-extraction evidence and
must not be treated as a fixed target. The new
`cli.modes.AssistantTurnPreparation` collaborator appears as point-in-time
evidence with priority index `136`.

T811 is not closed by this report alone. Closure still requires the ticket,
wiki current state, and wiki log to match this result, plus green focused tests,
`check`, and `wikiEvidenceCloseGate`.
