# Talos Harness Architecture and Rollout Plan

**Branch:** `feature/native-tool-pipeline`  
**Status:** starting-point architecture document for Talos + Opus  
**Scope:** Talos as a **local operator** for PC workspaces and general development.  
**Non-goals for this plan:** multi-agent orchestration, remote planners, background “dream” systems, browser swarms, or “fancy” agent ecosystems.

---

## 1. Why this document exists

Talos has crossed an important threshold:
- the **tool-calling pipeline is now native-first**
- write safety is significantly better than before
- approval UX and per-file verification exist
- unified conversation is pleasant and often creatively strong

But Talos still struggles in the exact places that decide whether a local operator feels **top-tier**:
- understanding **what to change**
- understanding **where to change it**
- converging on the correct file(s) quickly
- knowing when to **stop writing and verify**
- avoiding long repair spirals
- proving that the user’s task is actually complete

This document turns that reality into a concrete architectural plan.

---

## 2. Talos target definition

Talos is **not** trying to become a swarm or a theatrical multi-agent system.

Talos should become:
- a **local-first operator** for workspace tasks on a PC
- a strong **general development assistant**
- roughly “Claude Code at local level,” but designed around local trust, local files, and explicit user control
- excellent at bounded tasks inside a workspace
- safe enough that the user can trust it with local documents, code, and iterative edits

Talos should feel:
- local
- trustworthy
- competent
- deliberate
- not chaotic

The required leap from current Talos to target Talos is **not primarily model power**.
It is **execution harness quality**.

---

## 3. Source-of-truth current state (latest branch)

### 3.1 What Talos already has

Talos already has strong architectural seams for harnessing:

1. **AssistantTurnExecutor**
   - central turn orchestration
   - streaming/non-streaming dispatch
   - tool-loop entry
   - sanitization/truncation path

2. **ToolCallLoop**
   - native-first tool path
   - text fallback path
   - loop iteration cap
   - re-prompting after tools
   - central place where tool-use success/failure is visible

3. **TurnProcessor**
   - central tool execution gateway
   - approval gate integration
   - sandbox + registry execution
   - approval preview building

4. **ConversationManager**
   - history building
   - assist vs RAG compaction thresholds
   - compact sketch support

5. **ToolRegistry + ToolDescriptor**
   - canonical tool names
   - tool schemas
   - risk metadata
   - alias recovery for common model mistakes

6. **Per-file write/edit verification**
   - read-back verification
   - file-type heuristics for HTML/CSS/JS/JSON/YAML/XML

7. **Approval UX and progress UX**
   - write previews
   - tool progress feedback
   - verification surfaced back into loop output

### 3.2 What Talos currently exposes as tools

Current registered tool surface in bootstrap:
- `talos.read_file`
- `talos.write_file`
- `talos.edit_file`
- `talos.grep`
- `talos.list_dir`
- `talos.retrieve`

This matters because any harness plan that assumes browser automation, shell execution, test runners, or deployment tools is **not yet aligned** with Talos’s current tool reality.

### 3.3 What Talos still does poorly

Talos still has no explicit runtime notion of:
- inspect phase
- plan phase
- apply phase
- verify phase

So the model is still trusted to blend all of those itself.

That is the single biggest design weakness behind the latest conversation pain:
- diagnosis, planning, writing, and “done” are still too easy to blur together
- the runtime does not yet strongly enforce when Talos should inspect vs apply vs verify

### 3.4 Current compatibility debt that affects harnessing

The branch is now native-first, but some transitional complexity remains:
- JSON fallback is active
- XML remains compatibility-only in parser/filter/sanitize paths
- code-block extraction is disabled for writes, but code-block detectability still influences loop entry
- alias resolution in `ToolRegistry` is helpful for UX but can hide model weakness in evaluation mode
- session persistence is enabled by default, which is useful for users but dangerous for reproducible harness runs

---

## 4. Core diagnosis: where Talos is strong, where it is weak

## 4.1 Strong now

Talos is already strong in these ways:
- good conversational feel
- good aesthetic/design ideation
- much safer mutation path than before
- native-first tool transport
- decent approval and progress UX
- central runtime seams that are suitable for harness insertion

## 4.2 Weak now

Talos is still weak in these ways:

### A. Task-phase confusion
Talos does not explicitly know whether it is:
- inspecting
- planning
- applying changes
- verifying completion

### B. Task-level verification
Current verification is **per-file**, not **per-task**.
A file can be syntactically acceptable while the user’s actual task is still unfinished.

### C. Long-loop degradation
The loop has an iteration cap, but no strong notion of:
- repeated failure on the same file
- repeated missing parameter patterns
- retry degradation
- automatic “reset and reread current state” behavior

### D. Evaluation blindness
Talos does not yet appear to have a dedicated deterministic scenario harness for measuring quality over time.

### E. Narrow verification tool surface
Because Talos currently lacks command/browser/test tools, it cannot yet verify many runtime outcomes that developers actually care about.

---

## 5. Harness conclusion

Harness techniques are **not optional nice-to-haves** for Talos.
They are the next major architecture layer.

Talos now has enough runtime structure that harnesses can be added cleanly.
Without harnesses, Talos will remain:
- creative
- often pleasant
- sometimes impressive
- but still frustratingly unreliable in bounded file tasks

Harnesses are what turn Talos from a clever assistant into a reliable local operator.

---

## 6. Recommended harness stack for Talos

We should **not** pursue every imaginable harness equally.
For Talos’s current state and vision, the highest-value stack is:

1. **Scenario / parity harness**
2. **Runtime phase harness**
3. **Task-level verification harness**
4. **Tool-contract harness**
5. **Approval / permission harness**
6. **Session / memory harness**
7. **Identity / UX harness**

This order is intentional.

---

## 7. Harness-by-harness analysis

## 7.1 Scenario / parity harness (start here first)

### Purpose
Create deterministic, repeatable Talos task scenarios so progress is measurable.

### Why this should be first
This is the lowest-risk and highest-learning harness:
- it does not require immediate runtime behavior changes
- it turns subjective “Talos feels better” into objective evidence
- it reveals where the runtime actually fails before we over-engineer policy

### Where it fits architecturally
Best added as a **test/infrastructure layer**, not first as a live runtime behavior.

### What to add
Create a dedicated package/module, for example:
- `src/test/java/dev/talos/harness/...`
- or `dev.talos.harness` if a small runtime harness API is desired later

Core components:
- `ScenarioDefinition`
- `ScenarioWorkspaceFixture`
- `ScenarioApprovalPolicy`
- `ScenarioExpectation`
- `ScenarioRunner`
- optional `StrictToolMode`

### What to test first
Start with scenarios directly tied to known pain:
- broken BMI app → diagnose only
- broken BMI app → fix only `script.js`
- broken BMI app → full 3-file rewrite
- denied write approval
- unknown tool name emitted
- missing `path` on mutating tool
- code-block-only answer
- long repair loop / repeated warning case
- empty workspace → create Talos landing page

### What to remove / avoid
Do **not** begin with browser orchestration or background agents.
Those are not needed for the first useful harness layer.

### Expected gain
This harness becomes the scoreboard for every future Talos improvement.

---

## 7.2 Runtime phase harness (highest-value live runtime harness)

### Purpose
Make Talos explicitly operate in phases:
- `INSPECT`
- `PLAN`
- `APPLY`
- `VERIFY`

### Why this matters
This addresses the single biggest runtime pain:
Talos currently blurs diagnosis, design, file editing, and completion into one loop.

### Where it fits architecturally
Best insertion points:

#### 1. `AssistantTurnExecutor`
Role:
- determine initial harness policy for the turn
- capture whether the turn should start in `INSPECT` or `APPLY` or `VERIFY`
- decide whether a verify pass is mandatory after apply

#### 2. `ToolCallLoop`
Role:
- enforce phase transitions
- reject tool calls that are out-of-phase
- stop the model from applying writes during inspect/verify
- transition from `APPLY` → `VERIFY` when appropriate

#### 3. `TurnProcessor`
Role:
- hard gate tool execution by current phase
- keep approval semantics centralized

### What to add
New runtime concepts:
- `HarnessPhase` enum
- `HarnessPolicy` interface/class
- `TaskType` enum (e.g. `FILE_FIX`, `FULL_REWRITE`, `DIAGNOSE_ONLY`, `DESIGN_ONLY`)
- optional `TurnIntent` or `TaskContract`

Potential package:
- `dev.talos.harness.runtime`

### What to enrich
We should enrich the runtime with explicit policy rather than relying only on prompt instructions.

Recommended policy examples:
- `INSPECT`: allow only `read_file`, `list_dir`, `grep`, `retrieve`
- `PLAN`: no mutating tools
- `APPLY`: allow `write_file`, `edit_file`
- `VERIFY`: disallow mutations again; allow read/search/verification helpers

### What to remove / narrow
The current `AssistantTurnExecutor.hasAnyTextToolCalls()` still treats code-block-extractable answers as tool-like. This should be re-evaluated in a harnessed runtime.

Recommended direction:
- narrow or remove this behavior
- loop entry should be based on actual native or text tool calls, not on “this looks like a code artifact”

### Expected gain
This is the most direct fix for “it was hard to make Talos understand what to write and where.”

---

## 7.3 Task-level verification harness

### Purpose
Talos needs to know whether the **task** is complete, not only whether a file was written successfully.

### Why it matters
Current `ContentVerifier` is useful but local.
It does not prove that the user’s request was solved.

### Where it fits architecturally
Best introduced as a dedicated component rather than bloating `ContentVerifier` too far.

Recommended new component:
- `TaskVerifier`
- package: `dev.talos.harness.verify` or `dev.talos.runtime.verify`

### Best insertion point
After apply phase completes and before final “done” messaging.

Most natural integration point:
- `ToolCallLoop` after tool execution, before final answer acceptance
- optionally invoked by `AssistantTurnExecutor` if the turn contract says verification is mandatory

### What it should verify first
Because Talos does not yet have browser/shell tools in this branch, start with static workspace verification:
- expected files exist
- HTML references CSS/JS files that exist
- JS refers to IDs/classes that exist in HTML
- required elements exist for the task (e.g. result area, button, form fields)
- file names align (`style.css` vs `styles.css`)
- script/link references are not missing

### What to add later (discussion item)
If Talos later gains a controlled local command tool, verification can expand to:
- running test suites
- starting dev server
- checking console/runtime behavior

But that is not this first harness layer.

### What to remove / avoid
Do not pretend file-level syntax checks are enough.
Keep `ContentVerifier`, but stop treating it as full task verification.

### Expected gain
This is the harness that reduces false completion and improves trust.

---

## 7.4 Tool-contract harness

### Purpose
Measure and improve tool-use correctness separately from UX forgiveness.

### Why this matters
Talos currently tolerates many model mistakes via alias matching in `ToolRegistry`.
That is good for user experience, but bad for truthful evaluation.

### Where it fits
Two places:

#### Runtime strict mode
Add an optional strict harness/eval mode where:
- exact tool names are required
- alias repair is disabled
- malformed argument use is surfaced explicitly

#### Scenario harness
Record counts like:
- unknown tool emitted
- alias rescue needed
- missing required params
- repair path triggered
- verification warnings produced

### What to add
Potential additions:
- `ToolResolutionMode` (`FRIENDLY`, `STRICT`)
- `ToolCallMetrics`
- `ToolErrorCategory`

### What to remove / narrow
Do not remove alias handling from normal user mode.
But make it optional to disable for evaluation.

### Expected gain
You get truthful model-quality data without sacrificing everyday UX.

---

## 7.5 Approval / permission harness

### Purpose
Make approval behavior deterministic, testable, and trust-preserving.

### Why it matters
Talos’s privacy-first/local-first promise depends on approvals feeling reliable and predictable.

### Where it fits
`TurnProcessor.executeTool()` is already the central seam.
This is good architecture and should be preserved.

### What to add
Scenario coverage for:
- approve write
- deny write
- deny repeated write
- deny ambiguous edit
- huge content preview
- mutating tool with missing path

Potential enhancement:
- structured approval decision telemetry in harness/eval mode

### What to enrich
Approval UX can be enriched later with:
- diff previews for overwrite of existing files
- warning badges for verification risk or suspiciously large writes

### Expected gain
This harness reinforces user trust without requiring broad architectural changes.

---

## 7.6 Session / memory harness

### Purpose
Ensure Talos does not degrade badly over longer edit sessions and does not get unfair advantages/disadvantages from persistence.

### Why it matters
The compaction bug is already improved, but long-loop coherence remains critical.
Session persistence is useful for real use but dangerous for evaluation.

### Where it fits
Main seams:
- `ConversationManager`
- `TalosBootstrap`
- `JsonSessionStore`

### What to add
Harness/eval mode should support:
- no session auto-load
- no session auto-save
- optional compaction off
- optional fixed token budget
- deterministic clean-room runs

### What to enrich
Potential additions:
- artifact-aware compaction later (discussion item)
- pin recent changed-file state more aggressively during edit tasks

### Expected gain
This harness separates real product behavior from measurement behavior and makes long-turn regressions visible.

---

## 7.7 Identity / UX harness

### Purpose
Talos should not only be correct; it should consistently feel like Talos.

### Why it matters
The latest conversation showed that users value:
- natural flow
- aesthetic sensibility
- product identity

But execution discipline must not be traded away for pleasant tone.

### Where it fits
Mostly evaluation + prompt tests.

### What to test
- How Talos describes itself
- Whether it drifts into generic “I am an OpenAI model” type language
- Whether it stays local-first in self-description
- Whether explanations stay calm and operational rather than rambling or over-apologetic

### Expected gain
Keeps the product feeling coherent while the runtime becomes more disciplined.

---

## 8. What should be removed, added, and enriched

## 8.1 What should be removed or narrowed

### Remove or narrow
1. **Code-block detectability as loop-entry signal**
   - Current behavior is leftover complexity from earlier write fallback design
   - Recommendation: revisit and likely narrow/remove from `AssistantTurnExecutor`

2. **XML compatibility from active mental model**
   - XML is already compatibility-only
   - Do not let future harness logic depend on XML paths
   - Treat active architecture as native-first + JSON fallback only

3. **Evaluation dependence on alias rescue**
   - keep alias rescue for user mode
   - disable it in strict harness mode

4. **Evaluation dependence on persisted sessions**
   - scenario harness must run clean-room

## 8.2 What should be added

### New runtime concepts
- `HarnessPhase`
- `HarnessPolicy`
- `TaskType`
- `TaskContract`
- `TaskVerifier`
- `ToolResolutionMode`
- scenario harness package/classes

### New test/harness infrastructure
- `ScenarioRunner`
- `ScenarioWorkspaceFixture`
- `ScenarioApprovalPolicy`
- `StrictToolMode`
- `HarnessAssertions`

## 8.3 What should be enriched

### `ToolDescriptor` or sidecar harness metadata
Talos should eventually know more than name/schema/risk.

Possible enrichment options:
- allowed phases
- category (`READ`, `WRITE`, `SEARCH`, `VERIFY`)
- whether tool is mutating
- whether tool is verification-capable

This could be added either by:
- enriching `ToolDescriptor`, or
- creating a separate harness policy map to avoid large descriptor churn

### `ToolCallLoop`
Should be enriched with:
- phase enforcement
- stop/reset policies
- task-verification trigger

### `TurnProcessor`
Should be enriched with:
- phase-aware execution denial
- optional strict harness metrics/logging

### `ConversationManager`
Should be enriched later with:
- harness clean-room mode support
- optional artifact-priority retention strategy

---

## 9. Pain points and risk ranking

## Highest pain / highest risk
1. **No phase model**
2. **No task-level verifier**
3. **Long-loop degradation/reset not strong enough**
4. **No deterministic scenario harness yet**

## Medium pain / medium risk
5. **Alias rescue hides model weakness in evaluation**
6. **Session persistence contaminates reproducibility**
7. **Code-block detectability still influences loop entry**

## Lower pain / important later
8. **Identity drift checks**
9. **Artifact-aware compaction improvements**
10. **Richer verification once more tools exist**

---

## 10. Recommended implementation order

## Phase 0 — Documentation + evaluation baseline
Create the scenario harness foundation first.

**Why first:** measure before changing too much.

### Deliverables
- scenario harness package
- first 5–8 scenarios
- strict mode option for tool naming
- clean workspace/session execution

---

## Phase 1 — Runtime phase harness
Introduce phase-aware execution.

### Deliverables
- `HarnessPhase`
- `HarnessPolicy`
- phase transitions in `AssistantTurnExecutor` / `ToolCallLoop`
- phase gating in `TurnProcessor`

### Expected user win
Talos becomes easier to steer because the runtime helps separate inspect/plan/apply/verify.

---

## Phase 2 — Task-level verifier
Add `TaskVerifier` and make verify phase meaningful.

### Deliverables
- static cross-file checks for web/file tasks
- verify-after-apply rule for relevant task types
- structured verification result back into final answer

### Expected user win
Fewer false “done” moments.

---

## Phase 3 — Loop reset / degradation policy
Add smarter reset logic.

### Deliverables
- repeated failure detectors
- “reread current state” reset path
- iteration progress assessment

### Expected user win
Fewer exhausting repair spirals.

---

## Phase 4 — Strict evaluation mode and cleanup
Separate UX-friendly runtime from truthful benchmark runtime.

### Deliverables
- strict tool resolution mode
- no persistence mode
- code-block detection reevaluation
- compatibility cleanup where safe

---

## 11. Concrete implementation map (by file/class)

| Area | Current seam | Why it matters | Planned harness work |
|---|---|---|---|
| Turn orchestration | `AssistantTurnExecutor` | central turn entry and loop dispatch | phase initialization, stricter loop-entry semantics |
| Tool orchestration | `ToolCallLoop` | main native-first loop | phase enforcement, reset logic, verifier trigger |
| Tool execution | `TurnProcessor` | approval + sandbox + execution | phase-aware denial, harness telemetry |
| History | `ConversationManager` | compaction + history policy | clean-room harness mode, later artifact-aware tuning |
| Prompt building | `SystemPromptBuilder` | tool instructions and identity | later phase-aware instructions if needed |
| Tool contracts | `ToolRegistry` + `ToolDescriptor` | exact tool semantics | strict evaluation mode, optional phase metadata |
| File verification | `ContentVerifier` | per-file post-write checks | keep as local verifier, do not overload as full task verifier |
| Bootstrap wiring | `TalosBootstrap` | tool registry, loop, persistence | harness mode wiring, strict/eval config |

---

## 12. Things that should be discussed before implementation

These are not blockers for starting harness work, but they need explicit decisions.

### Discussion 1 — Should Talos eventually gain a controlled local command tool?
Without a shell/test-runner tool, Talos verification remains mostly static/file-based.
This is acceptable for now, but limits top-tier development verification.

### Discussion 2 — Should phase be visible to the user?
Options:
- invisible runtime-only state
- lightweight visible status (“Inspecting… Planning… Applying… Verifying…”)

### Discussion 3 — Should verification be automatic or opt-in?
For high-risk apply tasks, the recommendation is **automatic verify-after-apply**.
But user control and latency trade-offs need discussion.

### Discussion 4 — Should XML compatibility be fully removed later?
Current active architecture is native-first + JSON fallback. XML should not influence future harness design, but full compatibility removal can be decided separately.

### Discussion 5 — How far should strict evaluation mode diverge from user mode?
We need truthful quality measurement without making everyday Talos frustrating.

### Discussion 6 — Should task contracts be inferred or explicit?
For example, whether Talos infers `FILE_FIX` vs `FULL_REWRITE`, or whether the runtime derives a contract only from clear user instructions.

---

## 13. Final recommendation

If only one architectural move is taken next, it should be:

> **Build a scenario harness first, then introduce a runtime phase harness.**

Why this order:
1. scenario harness tells us where Talos really fails
2. phase harness addresses the biggest live usability problem
3. task-level verification then closes the trust gap

This is the most practical and highest-leverage path from current Talos to top-tier local operator Talos.

---

## 14. Summary in one sentence

Talos is now architecturally ready for harnessing, but it still needs **phase control, task-level verification, deterministic scenario evaluation, and cleaner runtime strictness** before it can feel consistently top-tier as a local operator.
