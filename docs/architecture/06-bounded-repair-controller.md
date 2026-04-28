# Bounded Repair Controller

Date: 2026-04-29
Status: T38 design for T39 implementation
Parent architecture: `docs/architecture/01-execution-discipline-and-local-trust.md`
Related designs:
- `docs/architecture/02-runtime-policy-ownership-map.md`
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/05-local-checkpoint-restore.md`

## 1. Purpose

The bounded repair controller is Talos's policy owner for post-failure repair
inside an already authorized workspace task.

Talos now has the pieces needed for disciplined repair:

- `TaskContract` keeps repair follow-ups mutation-capable when the prior task
  was a mutation task.
- `StaticTaskVerifier` can report concrete unresolved workspace problems.
- `StaticVerificationRepairContext` can pass those problems back into the next
  repair turn.
- `ToolCallExecutionStage`, `ToolCallRepromptStage`, and `FailurePolicy` can
  detect invalid edits, stale edits, no progress, and repeated failures.
- `LocalTurnTrace` and checkpointing can record what happened and provide a
  restore point before approved mutation.

Those behaviors are still spread across orchestration classes. The repair
controller v1 should give them one small policy shape without turning Talos
into a planner, a swarm, or a background autonomous repair daemon.

The controller must answer:

- is this turn allowed to repair?
- what previous verification or tool failure evidence is relevant?
- should Talos reread before retrying?
- should Talos prefer `write_file` over brittle `edit_file`?
- how many repair attempts are allowed?
- when should Talos stop?
- what can the final answer truthfully claim?

## 2. Current State

### `StaticVerificationRepairContext`

`StaticVerificationRepairContext.instructionFor(...)` already extracts a
repair checklist from a previous assistant answer that contains static
verification failure wording. It emits a system message beginning with
`[Static verification repair context]`.

Current strengths:

- carries previous verifier problems into the repair turn
- includes expected targets from the current `TaskContract`
- nudges small HTML/CSS/JS work toward complete `write_file` replacement when
  exact `edit_file` matching would be brittle
- avoids a planner

Current limits:

- it is prompt/context construction only
- it does not own attempt budgets
- it does not decide reread-before-retry
- it does not record a structured repair decision in trace
- it depends on parsing prior assistant text rather than a first-class prior
  `TaskOutcome` or local trace summary

### `ToolCallExecutionStage`

`ToolCallExecutionStage` executes parsed tool calls and records:

- successful mutation paths
- failed call signatures
- failed counts by tool and path
- empty edit argument failures
- stale edit failures after same-turn mutation
- suggestions after repeated `edit_file` failures

Current strengths:

- short-circuits exact duplicate failing edits
- blocks stale edit retries until a reread happens
- records enough loop state for failure policy decisions

Current limits:

- repair actions are embedded in execution flow
- suggestions are string diagnostics, not structured `RepairPlan` steps
- it cannot decide whether a later repair plan should prefer full-file writes

### `ToolCallRepromptStage`

`ToolCallRepromptStage` decides whether the loop should reprompt. It already
adds temporary system instructions for:

- stale edit repair requiring `read_file` first
- empty edit argument repair after the file was read
- current-task anchoring

Current strengths:

- stops after approval denial and policy denial
- avoids post-mutation chatter after all-success mutation iterations
- reprompts after partial success so the model sees failure messages
- removes temporary repair system messages after reprompt

Current limits:

- it owns repair prompts, failure-policy stop behavior, current-task anchoring,
  and reprompt mechanics in one class
- it has no structured repair attempt budget apart from loop/failure counts
- it cannot explain repair decisions as a first-class trace object

### `FailurePolicy`

`FailurePolicy` stops repeated failures by tool, path, empty edit arguments, or
no-progress iterations.

Current strengths:

- bounds repeated failures
- chooses `STOP_WITH_PARTIAL` when mutations have already succeeded
- avoids infinite invalid-edit loops

Current limits:

- it decides when to stop, not what repair plan to try before stopping
- it does not know verifier findings
- it does not know checkpoint or trace context

### `ExecutionOutcome`

`ExecutionOutcome` runs post-apply verification and shapes truthful final
outcomes:

- readback-only is not task completion
- failed static verification marks the task incomplete
- partial mutation remains partial
- warnings are recorded into local trace

Current limits:

- it does not produce structured repair input for the next turn
- it relies on final answer text for `StaticVerificationRepairContext`
- repair status in `LocalTurnTrace` is still a placeholder

## 3. Non-Goals

Bounded repair controller v1 does not add:

- shell execution
- browser automation
- MCP work
- multi-agent repair
- background repair loops
- an LLM classifier for repair permission
- automatic mutation without approval
- mutation outside the current `TaskContract`
- whole-workspace rewriting
- runtime/browser proof beyond existing static verification

The controller does not make Talos complete every task. It makes retry behavior
bounded, explainable, and truthful.

## 4. Design Principles

Repair v1 should be:

- contract-bound: repair cannot exceed `TaskContract.expectedTargets` and
  `forbiddenTargets`
- phase-aware: repair mutation only runs in `APPLY`
- permission-aware: no bypass of T35 allow/ask/deny policy
- checkpoint-aware: approved repair mutations still checkpoint before writes
- traceable: repair decisions appear in local trace
- bounded: small attempt budgets and stop conditions
- evidence-driven: verifier findings and tool errors become repair inputs
- reread-first when current content is uncertain
- truthful: failed repair reports remaining issues, not completion

## 5. Proposed Package And Types

Recommended package:

```text
dev.talos.runtime.repair
```

Recommended v1 types:

- `RepairPolicy`
- `RepairPlan`
- `RepairPlanStep`
- `RepairDecision`
- `RepairContext`
- `RepairAttemptBudget`
- `RepairEvidence`
- `RepairStopReason`

This is a small policy layer. It should not own model calls, tool execution, or
approval UI.

## 6. `RepairContext`

`RepairContext` is the input object passed to `RepairPolicy`.

Suggested fields:

```java
record RepairContext(
        TaskContract contract,
        ExecutionPhase phase,
        List<String> previousVerificationProblems,
        List<ToolCallLoop.ToolOutcome> priorToolOutcomes,
        Map<String, Integer> failureCountsByPath,
        Map<String, Integer> failureCountsByTool,
        Set<String> pathsReadThisTurn,
        Set<String> pathsMutatedSinceRead,
        Set<String> expectedTargets,
        Set<String> forbiddenTargets,
        boolean repairFollowUp,
        boolean staticVerificationFailed,
        boolean mutationAlreadySucceededThisTurn,
        Optional<String> checkpointId,
        Optional<String> traceId
) {}
```

T39 can start with a narrower constructor and grow only when tests require it.

## 7. `RepairPlan`

`RepairPlan` is the controller's output when a bounded repair attempt is
allowed.

Suggested fields:

```java
record RepairPlan(
        String planId,
        RepairPlanKind kind,
        List<RepairPlanStep> steps,
        RepairAttemptBudget budget,
        String userVisibleSummary,
        boolean mutationAllowed,
        boolean requiresApproval,
        boolean requiresCheckpoint,
        List<String> verifierProblemsUsed,
        List<String> expectedTargets,
        List<String> forbiddenTargets
) {}
```

Suggested `RepairPlanKind`:

- `STATIC_VERIFICATION_REPAIR`
- `INVALID_EDIT_ARGUMENT_REPAIR`
- `STALE_EDIT_REREAD_REPAIR`
- `NO_PROGRESS_STOP`
- `NOT_APPLICABLE`

`RepairPlan` is not a script. It does not directly call tools. It provides
bounded instructions and constraints for the existing model/tool loop.

## 8. `RepairPlanStep`

Suggested step types:

- `REREAD_TARGET`
- `APPLY_EXACT_EDIT`
- `WRITE_COMPLETE_FILE`
- `VERIFY_STATIC`
- `STOP_AND_REPORT`

Suggested fields:

```java
record RepairPlanStep(
        RepairStepType type,
        String targetPath,
        String reason,
        String instruction,
        boolean mustHappenBeforeMutation
) {}
```

Examples:

```text
REREAD_TARGET index.html
Reason: old_string failed after same-turn mutation changed the file.

WRITE_COMPLETE_FILE scripts.js
Reason: scripts.js is missing/placeholder and the file is small web code.

VERIFY_STATIC
Reason: previous verifier findings must be rechecked before claiming completion.
```

## 9. Reread-Before-Retry Rules

The controller should require `read_file` before another `edit_file` when:

- a prior `edit_file` for the path failed with `old_string not found`
- the same path was mutated earlier in the current turn
- the model attempts an exact duplicate edit signature after failure
- the file has not been read in the current repair turn
- static verifier failed due to HTML/CSS/JS linkage and the primary files have
  not been read in the repair turn

If reread is required:

- the next repair step is `REREAD_TARGET`
- no new `edit_file` for that path should execute until read evidence exists
- if the model ignores reread and repeats edit, failure policy can stop with
  a no-progress reason

For `write_file`, reread is strongly recommended but not always required:

- full replacement of a tiny missing/placeholder file can proceed after
  approval and checkpoint
- overwriting an existing target should prefer reread unless the user explicitly
  asked for a full overwrite

## 10. Full-File Write Preference

For small web files, repair v1 may prefer `write_file` when verifier findings
show whole-file coherence problems.

Candidate conditions:

- task is mutation-capable
- target extension is `.html`, `.css`, `.js`, `.jsx`, `.ts`, or `.tsx`
- target is missing, empty, placeholder, or expected-but-not-mutated
- verifier reports missing asset linkage, missing calculator/form controls, or
  duplicate assets
- repeated `edit_file` failures occurred for the same target

The plan should say:

```text
For this small web file, use talos.write_file with complete corrected file
content instead of brittle talos.edit_file old_string matching.
```

This is still a model instruction, not an automatic rewrite. Permission,
approval, checkpoint, tool validation, and static verification remain in force.

## 11. Attempt Budget

Recommended v1 budget:

- at most one `STATIC_VERIFICATION_REPAIR` plan per user repair turn
- at most one reread-required repair prompt per path per turn
- at most one empty-edit repair prompt per path per turn
- at most two failed mutating attempts per target before stop
- preserve existing `ToolCallLoop.DEFAULT_MAX_ITERATIONS`
- preserve `FailurePolicy` no-progress caps

Suggested `RepairAttemptBudget`:

```java
record RepairAttemptBudget(
        int maxRepairPlansPerTurn,
        int maxRepairPromptsPerPath,
        int maxFailedMutationsPerTarget,
        int maxNoProgressIterations
) {}
```

Defaults:

```text
maxRepairPlansPerTurn = 1
maxRepairPromptsPerPath = 1
maxFailedMutationsPerTarget = 2
maxNoProgressIterations = existing FailurePolicy default
```

## 12. Stop Conditions

Repair must stop when:

- the task contract is read-only, privacy-negated, or status-only
- the phase is not `APPLY`
- permission denies mutation
- approval is denied
- checkpoint creation fails with fail-closed enabled
- forbidden target would be mutated
- the model repeats a blocked/failed edit after reread instruction
- the same path reaches the failed mutation budget
- no progress has occurred for the configured limit
- static verification still fails after the bounded repair plan

Stop output must be truthful:

```text
The repair did not complete. No further edits were attempted because ...
Remaining static verification problems:
- ...
```

If any mutation succeeded before stop, the outcome is partial, not failed/no-op.

## 13. Verifier Findings As Repair Input

Verifier findings should become structured `RepairEvidence`, not only text.

T39 can start by parsing the existing `TaskVerificationResult` directly when
available. If only history text exists, it may reuse
`StaticVerificationRepairContext` as a compatibility bridge.

Suggested `RepairEvidence` fields:

```java
record RepairEvidence(
        String source,
        String status,
        List<String> problems,
        List<String> facts,
        List<String> expectedTargets,
        List<String> mutatedTargets
) {}
```

Mapping examples:

- `scripts.js: expected target was not successfully mutated`
  -> plan step `WRITE_COMPLETE_FILE scripts.js`
- `HTML does not link JavaScript file: scripts.js`
  -> plan steps `REREAD_TARGET index.html`, then fix linkage
- `Calculator/form task is missing a submit/calculate button`
  -> plan step for HTML structure repair
- `HTML links CSS file more than once`
  -> plan step remove duplicate asset reference

The controller should pass only concise problem summaries into repair context.
It should not include full file contents in trace or history.

## 14. Relationship To Existing Components

### `StaticVerificationRepairContext`

T39 should either:

- move its logic into `RepairPolicy`, or
- make it a renderer for `RepairPlan` while `RepairPolicy` owns decisions.

Do not keep expanding it as a standalone phrase bag.

### `ToolCallLoop`

`ToolCallLoop` remains the executor/reprompt loop. It should ask repair policy
for:

- whether to inject a repair instruction
- whether to stop after repeated failure
- whether to require reread before retry

It should not itself decide high-level repair strategy.

### `ToolCallExecutionStage`

This stage should keep recording facts:

- tool outcomes
- failed edit signatures
- path failure counts
- stale edit state
- mutation successes

Repair policy consumes those facts. Execution stage should not become the
planner.

### `FailurePolicy`

Failure policy can remain as the generic stop guard. Repair policy should use
it or produce compatible `FailureDecision` values. T39 should avoid two
competing stop systems.

### `ExecutionOutcome`

`ExecutionOutcome` remains the truth/outcome renderer. Repair policy should not
claim completion. It can attach repair status to `TaskOutcome` or local trace,
then `ExecutionOutcome` decides final visible truth from verification evidence.

### `LocalTurnTrace`

Local trace already has a repair summary placeholder. T39 should fill it.

Recommended trace fields:

- repair status: `NOT_APPLICABLE`, `PLANNED`, `ATTEMPTED`, `STOPPED`,
  `SUCCEEDED`, `FAILED`
- plan id
- plan kind
- problem count
- step count
- stop reason

Do not store full file contents or full replacement payloads.

### Checkpoint

Repair mutations use the same checkpoint behavior as any approved mutation.
Repair policy does not create checkpoints itself. It declares that mutation is
still required; `TurnProcessor` and `CheckpointService` enforce snapshotting.

## 15. User-Visible Behavior

Successful bounded repair should say:

```text
I applied the repair and static verification passed.
Changed files:
- ...
```

Partial repair should say:

```text
I applied some changes, but the task is still not verified complete.
Remaining static verification problems:
- ...
```

No-progress stop should say:

```text
I stopped the repair loop because the same edit kept failing.
No further file changes were applied after the last failure.
The next safe step is to reread the target file or overwrite it with complete
content if you want a full replacement.
```

The final answer must not say:

- working
- complete
- fixed
- done

unless verification evidence supports it.

## 16. Test Strategy For T39

Unit tests:

- `RepairPolicyTest`
  - static verification failure produces one repair plan
  - read-only/status/privacy contracts produce `NOT_APPLICABLE`
  - forbidden target is not included in repair plan
  - missing/placeholder small web file prefers `WRITE_COMPLETE_FILE`
  - stale edit failure requires reread before retry
  - repeated invalid edit reaches stop decision

- `RepairPlanTest`
  - plan serialization/redaction is stable
  - step order is deterministic
  - expected/forbidden targets are preserved

- `StaticVerificationRepairContextTest` or replacement tests
  - existing repair context behavior remains available
  - verifier problems are included
  - full file content is not included

- `ToolCallRepromptStageTest`
  - repair policy instructions are injected once
  - stale edit reread instruction still works
  - empty edit instruction still works
  - no duplicate repair prompt for same path

- `ExecutionOutcomeTest`
  - failed repair remains partial/failed
  - verification pass is required before completion claim

E2E scenarios:

- failed static web verification followed by repair writes missing JS and fixes
  HTML link
- repeated invalid edit stops cleanly with no false completion
- stale same-turn edit requires reread before retry
- status question after failed repair stays read-only and reports previous
  verified outcome
- privacy/no-workspace prompt cannot trigger repair

Manual Talos check:

1. create broken BMI workspace
2. ask Talos to repair it
3. approve mutation
4. if static verification fails, ask to fix remaining problems
5. verify repair plan is bounded, no blind edit loop occurs, and final answer
   is either verified complete or precise about remaining problems

## 17. T39 Implementation Order

Recommended sequence:

1. Add `dev.talos.runtime.repair` model types and pure policy tests.
2. Make `RepairPolicy` produce `RepairPlan` from current loop/verifier facts.
3. Render existing static verification repair instruction from `RepairPlan`.
4. Replace direct repair-instruction branching in
   `StaticVerificationRepairContext`/`ToolCallRepromptStage` only where tests
   require it.
5. Record repair summary into `LocalTurnTraceCapture`.
6. Add focused e2e scenarios.
7. Run installed manual Talos verification on a broken web workspace.

Do not refactor all repair-related code in one pass. T39 v1 should be a
behavior-preserving extraction plus one or two bounded improvements that are
covered by tests.

## 18. Risks

### Repair becomes planning

Mitigation: `RepairPlan` is a bounded constraint/instruction object. It never
executes tools directly and has small attempt budgets.

### Repair mutates outside scope

Mitigation: all repair plans carry expected and forbidden targets from
`TaskContract`; `TurnProcessor` remains enforcement.

### Repair hides model weakness

Mitigation: failed repair remains visible as partial/failed outcome; verifier
findings are preserved.

### Repair bloats `AssistantTurnExecutor`

Mitigation: T39 should create `dev.talos.runtime.repair` and avoid adding new
large phrase blocks to `AssistantTurnExecutor`.

### Repair conflicts with checkpoint/permission

Mitigation: repair policy never bypasses approval, permission, phase, or
checkpoint layers.

## 19. Open Questions

- Should repair plans be persisted in local trace only, or also attached to
  `TaskOutcome`?
- Should repair plans use current `TaskVerificationResult` directly, or should
  `ExecutionOutcome` expose a smaller stable repair evidence object?
- Should full-file write preference require a size threshold in v1?
- Should a successful `write_file` full replacement reset stale edit state for
  that path?
- Should `/last trace` show repair plan steps by default or only a summary?
- Should a repair follow-up after checkpoint restore use the restored state as
  a fresh baseline?

## 20. T39 Entry Checklist

Before implementing T39:

- add failing pure `RepairPolicy` tests first
- preserve all T22/T24/T25/T27/T37 boundary behavior
- preserve approval, permission, checkpoint, and trace semantics
- keep one controller/policy owner for repair decisions
- keep final outcome claims dependent on verification evidence
- avoid shell, browser, MCP, multi-agent, or background autonomy work
