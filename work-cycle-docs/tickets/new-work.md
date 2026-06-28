# This new-work ticket is my Talos vision

> Historical context after 0.9.6: this document was an earlier architecture
> vision. After 0.9.6, TaskContract and phase machinery exist on the active
> branch. The canonical post-0.9.6 milestone plan is now
> `docs/architecture/01-execution-discipline-and-local-trust.md`. Keep this
> document as historical context, but do not treat stale
> missing-TaskContract/missing-phase statements as current branch truth.

**Talos can become a reference architecture, but it is not there yet.**
It is currently a **strong prototype with promising architecture**, not yet a “study this as the clean pattern” system.

That is not an insult. It means you are at the exact dangerous point where the project can either become:

1. a respected local-first Java assistant with an architecture people can learn from, or
2. a clever custom CLI full of accumulated patches, retries, and special cases.

The next path matters a lot.

## My corrected diagnosis

The README is now strong. It correctly says Talos is a **local-first CLI workspace assistant** with retrieval, approval-gated file operations, traces, context handling, and verification-oriented outcomes. It explains that Talos can inspect files, retrieve local context, and apply changes through an approval-gated tool loop. It also gives a simple turn model: inspect workspace, retrieve context when needed, call local tools, then report/trace/persist.

So the **product identity is now basically right**.

The engineering evidence loop is also strong. Your Gradle build has Java 21, deterministic scripted E2E lane, candidate test lanes, JaCoCo verification, Qodana, Gitleaks, OSV scanner, and machine-readable summary/report generation. The comment around `writeSummarySoft` is particularly good because it says malformed evidence should produce an explicit failure artifact instead of destroying the candidate packet. That is professional engineering thinking.

The runtime is also stronger than before. `ToolCallLoop` has a native/text tool-call path, iteration cap, strict mode, tool outcomes, failure counts, mutating-success counts, read paths, alias rescue counters, and loop summaries.  `TurnProcessor` has explicit approval gate/policy wiring, sandbox execution, scope guarding, mutation-intent guarding, template-placeholder rejection, approval previews, and audit capture.  `AssistantTurnExecutor` now has truth layers: synthesis retry, mutation-claim annotation, denied/partial mutation summaries, missing-mutation retry, inspect-under-completion checks, and streaming no-tool truthfulness handling.

That is real progress.

But here is the hard truth:

**Talos currently has discipline mechanisms, not yet a discipline architecture.**

That is the one-sentence diagnosis.

## The main risk

Your runtime is becoming safer, but it is also accumulating many local correction mechanisms:

* retry if deflected
* retry if mutation was requested but not performed
* annotate if mutation was claimed but not performed
* summarize denied mutation
* summarize partial mutation
* block mutating tools when the request was read-only
* block placeholder content
* warn on off-scope mutation
* track tool outcomes

These are good individually. But if they remain scattered as “truth patches,” Talos will become harder to reason about.

A reference architecture needs a **small number of central concepts** that explain all these behaviors.

Right now the concept you need is:

> **Execution discipline.**

Not as branding. As the runtime model.

The agent book source supports this direction: LLMs can express intent, but they cannot act unless surrounded by orchestration that executes actions. It also frames the processing loop as the place where planning, tool calls, and task progress happen.  Your job is to make Talos’s processing loop disciplined, local-first, and inspectable.

The Claude Code leak article points to the same lesson from the production side: the impressive parts are not vague “agent magic,” but specific runtime details like failure caps, security checks, terminal rendering, prompt-cache behavior, and operational guardrails.

So the path is not “add more AI features.”

The path is:

> **Turn Talos into the clearest Java example of a disciplined local agent runtime.**

## The one true path

### Phase 0 - Stop and define the architecture spine

Before more implementation, create one canonical architecture document:

```text
docs/architecture/01-execution-discipline.md
```

This must become the source of truth.

Do not make it long. Make it sharp.

Define Talos like this:

> **Talos is a local-first Java workspace assistant built around execution discipline: it inspects before acting, retrieves before guessing, asks before writing, verifies before claiming completion, and preserves evidence after the turn.**

Then define the core disciplines:

```text
Inspection Discipline    -> understand workspace state before conclusions
Retrieval Discipline     -> use local context before guessing
Tool Discipline          -> tools are typed, bounded, phase-aware actions
Approval Discipline      -> mutation requires explicit user control
Verification Discipline  -> task completion must be checked, not assumed
Evidence Discipline      -> every serious candidate produces reviewable artifacts
Session Discipline       -> memory helps continuity without corrupting evaluation
Failure Discipline       -> loops stop, reset, or downgrade instead of spiraling
```

This is not marketing. This is the architecture skeleton.

**Acceptance criterion:** a new engineer should be able to read this doc and understand what Talos is trying to enforce before seeing the code.

---

### Phase 1 - Build the scenario discipline first

Your own plan already says scenario/parity harness should come first because it turns “feels better” into evidence. That is correct.

But I would rename the concept publicly:

* internal term can still be `harness`
* architecture term should be **scenario discipline**

Build:

```text
ScenarioDefinition
ScenarioWorkspaceFixture
ScenarioApprovalPolicy
ScenarioExpectation
ScenarioRunner
ScenarioResult
ScenarioReport
StrictToolMode
```

Start with 8 scenarios:

```text
1. Explain README from workspace evidence
2. Inspect a small HTML/CSS/JS app before changing it
3. Change only index.html after approval
4. Deny write approval and recover honestly
5. User asks read-only question; model attempts write; runtime blocks it
6. Model claims file changed but no mutation succeeded
7. Partial mutation: one write succeeds, one fails
8. Long loop / repeated failure triggers reset or stop
```

This should be deterministic and not depend on a live local model at first. Use scripted LLM outputs. Your build already has an E2E lane, candidate lanes, and report generation, so connect scenario results into that evidence system instead of creating a separate island.

**Acceptance criterion:** every architecture claim about discipline must have at least one scenario proving it.

If you cannot test a discipline, it is not architecture yet. It is aspiration.

---

### Phase 2 - Create the runtime phase model

This is the most important runtime change.

Your current architecture doc admits Talos still lacks explicit runtime phases: inspect, plan, apply, verify. It also says this is the core weakness behind blurred diagnosis/planning/writing/done behavior.

So implement:

```java
enum ExecutionPhase {
    INSPECT,
    PLAN,
    APPLY,
    VERIFY,
    RESPOND
}
```

Then implement a policy:

```java
record PhasePolicy(
    ExecutionPhase phase,
    Set<ToolCategory> allowedToolCategories,
    boolean mutationAllowed,
    boolean approvalRequired,
    boolean verificationRequired
) {}
```

Tools should not be judged only by name and risk. They need discipline metadata:

```text
READ
SEARCH
RETRIEVE
MUTATE
VERIFY
```

The current tool surface is perfect for this because it is small:

```text
read_file
list_dir
grep
retrieve
write_file
edit_file
```

Your docs correctly warn that browser/shell/test-runner assumptions are not aligned with the current tool reality.  Keep it that way for now.

**Acceptance criterion:** if Talos is in `INSPECT`, `write_file` and `edit_file` cannot execute even if the model calls them. If Talos is in `VERIFY`, mutation is also blocked. If Talos is in `APPLY`, mutation still goes through approval.

This is where discipline becomes real.

---

### Phase 3 - Add TaskContract

Without a task contract, Talos is still interpreting raw user text on every turn.

Add:

```java
record TaskContract(
    TaskType type,
    boolean mutationRequested,
    boolean mutationAllowed,
    boolean verificationRequired,
    Set<Path> expectedTargets,
    Set<Path> forbiddenTargets,
    RiskLevel risk,
    String originalUserRequest
) {}
```

Start with simple task types:

```text
READ_ONLY_QA
WORKSPACE_EXPLAIN
DIAGNOSE_ONLY
FILE_EDIT
FILE_CREATE
MULTI_FILE_REWRITE
VERIFY_ONLY
```

Do not over-engineer this with an LLM classifier immediately. Begin with deterministic derivation from existing routing/mutation-intent logic, then allow the model to propose a contract later.

Your `TurnProcessor` already has mutation-intent guarding.  That should move upward into `TaskContract`, so mutation permission is not a local check buried in tool execution. Tool execution should enforce the contract, not infer the whole task.

**Acceptance criterion:** the runtime can print/debug:

```text
TaskContract:
  type: FILE_EDIT
  mutationAllowed: true
  verificationRequired: true
  expectedTargets: [index.html]
```

If Talos cannot explain the task contract, it cannot claim disciplined execution.

---

### Phase 4 - Centralize truth layers into a TaskOutcome model

Right now, `AssistantTurnExecutor` has many valuable truth protections. But they are spread across post-processing functions.

Create a central outcome object:

```java
record TaskOutcome(
    TaskContract contract,
    List<ToolOutcome> toolOutcomes,
    MutationOutcome mutationOutcome,
    VerificationOutcome verificationOutcome,
    CompletionStatus completionStatus,
    List<TruthWarning> warnings
) {}
```

Then the final answer should be generated from `TaskOutcome`, not from scattered annotations.

Possible statuses:

```text
COMPLETED_VERIFIED
COMPLETED_UNVERIFIED
PARTIAL
BLOCKED_BY_APPROVAL
BLOCKED_BY_POLICY
FAILED
READ_ONLY_ANSWERED
```

This replaces many ad hoc truth branches with a single explainable model.

**Acceptance criterion:** every final answer can say, internally or visibly:

```text
Outcome: PARTIAL
Reason: edit_file succeeded for index.html, write_file failed for script.js
Verification: not passed
```

That is reference-architecture quality.

---

### Phase 5 - Add TaskVerifier, but start static

Your current docs correctly say per-file verification is not task-level verification. A file can be syntactically acceptable while the user’s task is still unfinished.

Start with a static verifier:

```java
interface TaskVerifier {
    VerificationOutcome verify(TaskContract contract, WorkspaceSnapshot snapshot, List<ToolOutcome> outcomes);
}
```

Initial checks:

```text
Expected file exists
Expected target changed
Forbidden target not changed
HTML links existing CSS/JS
JS references existing DOM ids/classes
No unexpected generated file
No placeholder content
No empty overwrite
No claim without mutation
```

Do not add shell execution yet.

I know it is tempting to add a local command tool, test runner, browser, or MCP. Do not do it before this. Static verification gives you 70% of the trust gain with 20% of the risk.

**Acceptance criterion:** Talos cannot say “done” for file-changing tasks until `TaskVerifier` has produced a structured result.

---

### Phase 6 - Add failure discipline

This is where you become more serious than most hobby agents.

Your current `ToolCallLoop` has an iteration cap and rich outcomes.  But the architecture doc still says long-loop degradation/reset is weak.

Add a formal failure policy:

```java
record FailurePolicy(
    int maxIterations,
    int maxSameToolFailures,
    int maxSamePathFailures,
    int maxNoProgressIterations,
    boolean rereadBeforeRetry,
    boolean downgradeToInspectOnDrift
) {}
```

Track:

```text
same tool failed repeatedly
same file failed repeatedly
same missing parameter repeated
mutating target changed unexpectedly
read paths do not include target before edit
no progress after N iterations
```

Actions:

```text
RESET_TO_INSPECT
REREAD_TARGET
ASK_USER
STOP_WITH_PARTIAL
BLOCK_MUTATION
```

The Claude Code leak’s compaction example is relevant: a simple failure cap reportedly stopped huge wasted work.  Talos needs the same attitude locally: failure control is architecture, not cleanup.

**Acceptance criterion:** repeated failures produce a controlled stop/reset, not another blind model retry.

---

### Phase 7 - Make CLI interaction show discipline

A reference architecture is not only code. Users must feel the design.

The README already shows the turn model clearly.  The CLI should now display it.

Example:

```text
[inspect] Reading README.md
[retrieve] Searching local index
[plan] Target: index.html
[approval] edit_file requires confirmation
[apply] 1 edit applied
[verify] HTML references checked
[outcome] COMPLETED_VERIFIED
```

This should not be noisy. It should be calm and optional/configurable.

Add:

```text
talos doctor
talos status --deep
talos explain-last-turn
talos scenarios run
talos quality
```

The most important command for reference architecture is probably:

```text
talos explain-last-turn
```

It should show:

```text
TaskContract
Phases visited
Tools called
Approvals
Files changed
Verification result
Warnings
Outcome
```

This makes Talos teachable.

**Acceptance criterion:** a user can inspect how Talos reached a result without reading logs.

---

### Phase 8 - Fix documentation as architecture, not decoration

Your README currently links `work-cycle-docs/work-test-cycle.md`, but that file was not retrievable through the connector when I checked. The README references it directly.  This is small but important: broken architecture links damage credibility.

Create a clean architecture doc structure:

```text
docs/architecture/
  00-vision.md
  01-execution-discipline.md
  02-runtime-loop.md
  03-task-contract.md
  04-tool-system.md
  05-approval-and-safety.md
  06-verification.md
  07-session-memory.md
  08-scenario-discipline.md
  09-evidence-loop.md
```

Every doc must be short and follow the same template:

```text
Problem
Design
Main classes
Invariants
Failure modes
Scenarios proving it
Limitations
```

Do not write huge essays. A reference architecture is readable.

**Acceptance criterion:** someone can understand Talos’s architecture in 45 minutes.

---

### Phase 9 - Clean the build/report architecture

Your Gradle file has strong quality/reporting logic, but it is becoming heavy.  For a reference architecture, consider moving reporting logic into:

```text
buildSrc/
```

or a small Gradle convention plugin:

```text
build-logic/
  talos-quality.gradle.kts
  talos-reports.gradle.kts
```

Why? Because if the build file becomes a giant procedural script, people will admire the capability but not copy the pattern.

The evidence loop is good. Its packaging should become cleaner.

**Acceptance criterion:** build/reporting logic is modular enough that another Java project could copy the pattern.

---

## What not to do

This is important.

Do **not** focus next on:

```text
multi-agent systems
browser control
background autonomous workers
MCP-first marketing
shell command execution
plugin ecosystem
more model providers
cloud features
fancy UI
```

Those are tempting, but they will dilute Talos.

Talos’s best chance is not to become bigger. It is to become **more disciplined**.

Your own architecture doc is correct to reject swarms, remote planners, browser swarms, and fancy agent ecosystems.  Stay there.

## The brutal claim check

### Your claim: “Talos can be an architecture inspiration.”

**True, but conditional.**
It becomes true only if you formalize discipline into runtime concepts, not just docs and patches.

### My earlier claim: “Make Talos teachable.”

**Still true, but incomplete.**
Teachable is not enough. It must also be **measurable** through scenarios and **enforced** through runtime policy.

### Your claim: “Qwen 2.5 14B behaves well.”

**Useful, but not architecture.**
A model behaving well is not a reference system. The reference system is what keeps behavior bounded when the model behaves badly.

### My earlier claim: “Quality reports are a trust feature.”

**True.**
But they should be integrated into scenario discipline and release evidence, not remain just local Gradle extras.

### Current branch claim: “Talos is no longer just RAG.”

**True.**
The README supports that.

### Current branch claim: “Talos is already top-tier.”

**False.**
It still lacks first-class phase control, task-level verification, and failure discipline. Your own architecture doc says this.

## The final plan, in exact order

### Step 1 - Name the architecture

Create `execution-discipline.md`.

Outcome: Talos has a clear architectural doctrine.

### Step 2 - Build scenario discipline

Create deterministic scenarios and scenario reports.

Outcome: progress becomes measurable.

### Step 3 - Add `TaskContract`

Make every turn produce or infer a task contract.

Outcome: Talos knows what kind of task it is executing.

### Step 4 - Add `ExecutionPhase`

Enforce `INSPECT → PLAN → APPLY → VERIFY → RESPOND`.

Outcome: Talos stops blending thinking, acting, and claiming done.

### Step 5 - Add tool phase metadata

Tools become allowed/blocked by phase and contract.

Outcome: tool discipline becomes enforceable.

### Step 6 - Add `TaskOutcome`

Centralize mutation results, warnings, verification, and completion status.

Outcome: truth layers stop being scattered patches.

### Step 7 - Add static `TaskVerifier`

Start with file/web/workspace checks.

Outcome: Talos stops claiming completion without task-level checking.

### Step 8 - Add failure/reset policy

Stop repeated blind retries.

Outcome: Talos becomes more controlled under model failure.

### Step 9 - Expose discipline in CLI

Show phases, approvals, verification, and outcomes.

Outcome: users feel the architecture.

### Step 10 - Clean architecture docs and build logic

Make the repo readable and copyable.

Outcome: Talos becomes reference material, not just source code.

## What Talos becomes after this

If you finish this plan well, Talos can honestly be described as:

> **A discipline-first local Java workspace assistant: a reference architecture for local AI systems that inspect before acting, retrieve before guessing, ask before writing, verify before claiming completion, and preserve evidence after each turn.**

That is the thing.

Not “better than Claude Code.”
Not “Java agent framework.”
Not "retrieval-only CLI."
Not “multi-agent system.”

The category is:

> **disciplined local AI operator**

And the reference value is:

> **how to engineer local trust around an LLM, not how to make an LLM sound smart.**

That is the one true path I see.
