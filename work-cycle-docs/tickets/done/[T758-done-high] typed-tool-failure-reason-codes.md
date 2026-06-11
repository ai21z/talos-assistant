# T758 - Typed Tool Failure Reason Codes

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (outcome-truth infrastructure: classification decoupled from prose)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: Claude

## Problem

Outcome and repair classification sniffed human-readable error prose:
`ToolExecutionFailureClassifier` (startsWith "User did not approve " /
"Path not allowed before approval" / ...), `ToolOutcomeFailureShape`
(contains "old_string" + "empty"/"present", "append-line write_file", ...),
`CommandOutcomeRenderer` (startsWith "command timed out:"),
`MutationFailureAnswerRenderer` and `ProtectedReadAnswerGuard` (startsWith
"User did not approve "), and `TurnProcessor.preApprovalBlockReason`.
Rephrasing any of these messages silently disabled repair ladders or
outcome truth policy — outcome classification was string-coupled to
renderer literals (2026-06-10 evaluation, roadmap item W2.3). Notably,
`EditFilePreApprovalGuard` already HAD a typed Kind enum that was flattened
to prose at the ToolOutcome boundary and re-derived downstream by sniffing.

## Architecture Metadata

Capability: failure classification transport
Operation(s): all
Owning package/class: `dev.talos.tools.ToolFailureReason` (closed enum,
15 constants), `ToolError` (carries reason), `ToolCallLoop.ToolOutcome`
(carries failureReason; `withFailureReason` copier), `ToolOutcomeFactory`
(maps reasons on both transport paths incl. the EditFilePreApprovalGuard
Kind mapping)
New or changed tools: none (FileEditTool/RunCommandTool producers tagged)
Risk, approval, and protected paths: behavior intentionally invariant;
producers tagged at TurnProcessor (permission/approval denial, the four
pre-approval validators, edit-argument and exact-edit-match validators),
StaticWebRepairPathGuard outcome site, AppendLinePreApprovalGuard outcome
site, FileEditTool, RunCommandTool (timeout)
Checkpoint, evidence, verification, and repair: repair planners now keyed
off reasons (via ToolOutcomeFailureShape)
Outcome and trace: audit prose from preApprovalBlockReason byte-identical
Refactor scope: no prose rewording this ticket (model-facing nudge text is
load-bearing for live model behavior; decouple first, reword later)

## Design Decisions

- Enum, not string constants: closed vocabulary, typo-proof; ToolOutcome is
  never serialized so there is no wire cost. `ToolError.code` stays the
  coarse class; `reason` is the orthogonal fine-grained cause.
- Only sniffed families get constants; producers without a downstream
  classifier keep NONE. NONE classifies like an unmatched message did:
  generic failure (fail-closed default).
- Sniffing DELETED, no fallback: all producers are in-repo; prose-freedom
  proof tests pin that prose alone no longer classifies.
- MutationFailureAnswerRenderer's MUTATION_CLAIM_MARKERS are out of scope:
  they classify MODEL prose, which cannot carry typed codes.
- Critical plumbing fix found during migration:
  `ProtectedContentPolicy.sanitizeToolResult` rebuilt ToolError with the
  two-arg constructor, silently DROPPING the reason for every sanitized
  failure — redaction now rewrites prose, never classification.

## Known Residual

- `ExpectedTargetScopeRepairPlanner.looksLikeExpectedTargetScopeFailure`
  consumes `FailureDecision.reason()` free text and additionally EXTRACTS
  data from it (expected-target list, backticked failed target). Migrating
  it needs FailureDecision to carry structured targets (Wave-5 typed
  OutcomeSignals). Documented in-code; the phrase is load-bearing until
  then.

## Tests / Evidence

- Prose-freedom proofs in `ToolExecutionFailureClassifierTest`: arbitrary
  message + typed reason classifies; old magic message + NONE does not
  (sniffing is provably gone).
- `ToolOutcomeFactoryTest` updated for new factory signatures.
- 13 test files migrated: fabricated failure inputs now attach typed
  reasons exactly as real producers do (ExecutionOutcomeTest,
  AssistantTurnExecutorTest nests, MutationFailureAnswerRendererTest,
  ProtectedReadAnswerGuardTest, CommandOutcomeRendererTest, repair-planner
  and reprompt-decision tests, ToolFailure accounting/signals tests,
  ToolLoopResultSummaryFormatterTest). No assertions weakened; tests
  pinning the untyped-prose fail-closed default left as-is.
- Behavior-invariance net: full unit suite (4,8xx tests) and the e2e
  scenario pack green with byte-identical producer prose.

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green.
- Residual-sniffing grep: the magic literals appear only in producers and
  in the one documented FailureDecision residual, never in classifier
  conditions.
