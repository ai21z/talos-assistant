# Wave 5 structural decomposition closeout, proposed

Status: proposed for owner ratification
Branch: v0.9.0-beta-dev
Talos version: 0.10.5
Prepared: 2026-06-17

## Decision

Proposed decision: close the Wave 5 structural-decomposition arc as complete as
of T831 result-formatting extraction.

This is not a claim that every hotspot vanished or that every candidate is at
its theoretical floor. It is a deliberate stopping decision based on completed
high-value structural cuts, current ranking evidence, diminishing returns, and
the risk of over-fragmenting already-characterized helper surfaces.

Owner ratification is still required.

## Ranking Basis

The decision is based on the post-T831 architecture evidence first produced at
implementation commit `93313de56b22a93a02af9515828e00a3a77947f8`.

Later T832 commits were test, ticket, report, and wiki-only. They did not change
production `src/main` code. The current regenerated architecture report anchors
to `4de4bba6ecdd512ec487543501ac4e1c69d6fbdf`, but the relevant ranking values
remain the same for this decision.

Current ranking evidence:

| Candidate | Priority index | Interpretation |
| --- | ---: | --- |
| `runtime.ToolCallLoop` | 334 | Mostly public facade and fan-in floor. `LoopResult` and `ToolOutcome` records are pinned public, so this is not a clean next extraction target. |
| `runtime.toolcall.LoopState` | 293 | Mostly floor plus broad direct-construction blast radius. It remains deferred because hardening it now would touch many tests and surfaces. |
| `runtime.toolcall.ToolCallSupport` | 233 | Residual fanOut is about 114 in small fragmented clusters. Native-call conversion and result formatting were the high-value cuts. Continuing now risks over-fragmentation. |
| `runtime.toolcall.ToolCallExecutionStage` | 119 | At the practical floor after the guard-chain extraction. Done for this arc. |
| `cli.modes.ExecutionOutcome` | 99 | Large file, 753 lines, but current generated priority is below the tool-loop internals. Deferred despite size. |

Confidence label for the ranking evidence: `INFERRED_REVIEW`.

## Closed Structural Arcs

### Executor arc, T811 through T818

Closed. The executor seam work characterized and extracted turn preparation,
model dispatch, tool-loop outcome resolution, no-tool outcome resolution, and
prompt-instruction adapter ownership.

This left `AssistantTurnExecutor` as an orchestrator with known residual
responsibilities rather than a warehouse for every policy and rendering branch.

### Core/tools cycle arc, T819 through T822

Closed. The `core <-> tools` cycle was scoped and then removed through:

- `ContextItem` tool-result adapter and neutral privacy seam,
- `SystemPromptBuilder` prompt-facing tool catalog seam,
- `RagService` protocol-text seam.

The generated package SCC list became empty after T822. This was the headline
package-cycle goal for the arc.

### ToolCallLoop facade arc, T823 and T824

Closed. `ToolCallLoop` orchestration was characterized, then the internal
orchestration body moved into package-private `ToolCallLoopEngine`.

The public `ToolCallLoop` facade, public `LoopResult`, public `ToolOutcome`, and
public run overloads stayed stable.

### ToolCallExecutionStage arc, T826 and T828

Closed. T826 directly characterized `ToolCallExecutionStage.execute(...)`. T828
then extracted the pre-execution guard chain into
`ToolCallPreExecutionGuardChain`.

The public `execute(LoopState, ParsedCalls)` surface and public
`IterationOutcome` record stayed stable. Privacy, trace, approval, ledger,
mutation, failure, and edit-repair ordering remained guarded.

### ToolCallSupport split, T829 through T831

Closed for this structural arc. T829 scoped the support surface, T830 extracted
native-call conversion, and T831 extracted prompt-visible result formatting.

The remaining `ToolCallSupport` seams are small and fragmented. They are not
abandoned, but they are no longer the best Wave 5 structural target.

### Evidence-order tooling, T827

Closed. T827 made Qodana summary generation deterministic for architecture
intelligence and wiki-evidence gates without running Qodana or changing Qodana
policy.

## Behavior-Preservation Record

Every production extraction in this arc was treated as behavior-preserving and
landed with focused characterization and broad gates.

Privacy and trust-sensitive gates remained green through the relevant cuts,
including protected-read scope, tool-result model-context handoff, local trace,
approval denial, context ledger, mutation accounting, and edit-repair accounting
coverage.

This record does not replace the done-ticket evidence. The done tickets remain
the authoritative per-ticket acceptance ledger.

## Deferred, Not Abandoned

### LoopState hardening

Deferred because it has broad direct-construction blast radius and a high
public/internal fixture footprint. Hardening it should be scoped separately.

### ExecutionOutcome relocation

Deferred because current generated evidence ranks it at 99 despite its size.
Line count alone is not enough to outrank tool-loop and runtime policy evidence.

### Remaining ToolCallSupport seams

Deferred because the high-value seams are done. Remaining clusters are small,
fragmented, and would risk over-fragmenting the support surface.

### Retry extraction

Deferred because T829 framed retry/request extraction as belonging with retry
policy, not as a mechanical `ToolCallSupport` cleanup.

### In-turn compaction quality

Separate behavioral track. T832 showed real compaction usage and same-turn
re-read proxy evidence, but no proof of measurable answer-quality harm. Phase 2
gist-in-stub remains optional and owner-deferred.

## Explicit Non-Claims

- This does not claim all Wave 5 candidates are at their theoretical floor.
- This does not claim `ToolCallSupport` has no reducible fanOut. It still carries
  about 114 residual fanOut.
- This does not claim `LoopState` is clean or final.
- This does not claim `ExecutionOutcome` relocation is unnecessary.
- This does not authorize Phase 2 compaction behavior changes.

## Ratification Criteria

The owner can ratify this proposed decision if they accept:

- the high-value structural cuts are complete,
- remaining cuts are lower leverage or higher fragmentation risk,
- T832 compaction quality is a separate behavioral track,
- future work should return to LoopState, ExecutionOutcome, retry extraction, or
  ToolCallSupport only through new scoped tickets.
