# Talos Harness Source of Truth for independent review

**Branch:** `chore/codebase-cleanup-refactor`  
**Purpose:** give independent review one clear, aligned document that separates **hard evidence**, **useful source material**, and **Talos-specific architectural judgment**.  
**Audience:** human reviewer + independent review  
**Status:** working source-of-truth companion to `docs/new-architecture/talos-harness-plan.md`

---

## 1. Why this document exists

We have gathered many repos, articles, and discussions.
That is useful, but also dangerous.

If independent review receives only a pile of sources, it may copy mechanisms that are:
- product-specific
- cloud/SaaS-specific
- anti-user
- over-engineered for Talos
- impressive in appearance but wrong for a **local-first** operator

This document exists to prevent that.

It defines:
1. what Talos is
2. what Talos is not
3. which sources matter most
4. what each source is good for
5. what should be copied, adapted, or rejected
6. where evidence ends and architectural judgment begins

---

## 2. Talos identity (non-negotiable)

Talos is **not** trying to become a swarm or theatrical multi-agent system.

Talos should become:
- a **local-first operator** for workspace tasks on a PC
- a strong **general development assistant**
- roughly **local coding assistant at local level**, but designed around local trust, local files, and explicit user control
- excellent at **bounded tasks** inside a workspace
- safe enough that the user can trust it with local documents, code, and iterative edits

Talos should feel:
- local
- trustworthy
- competent
- deliberate
- not chaotic

The required leap from current Talos to target Talos is **not mainly model power**.
It is **execution harness quality**.

This is the main architectural lens.
Every external mechanism must be judged through it.

---

## 3. Current Talos truth from our own repo

The current Talos architecture plan already says the biggest live problems are:
- no explicit **phase model**
- no **task-level verifier**
- weak handling of **long-loop degradation / reset**
- no dedicated **deterministic scenario harness**

It also identifies the main useful runtime seams:
- `AssistantTurnExecutor`
- `ToolCallLoop`
- `TurnProcessor`
- `ConversationManager`
- `ToolRegistry` + `ToolDescriptor`
- per-file verification
- approval / progress UX

That means Talos is already structurally ready for harness work.
The problem is **not** lack of architecture seams.
The problem is missing harness layers.

Primary local reference:
- `docs/new-architecture/talos-harness-plan.md`

Current working baseline for harness preparation:
- `chore/codebase-cleanup-refactor`
- This branch includes the codebase-cleanup stream through `CCR-015`, so
  harness work should use it rather than the older `feature/native-tool-pipeline`
  snapshot as the local structural baseline

This document should be treated as the main internal architecture plan.
The current document you are reading is the **source-evaluation companion**.

---

## 4. Evidence model for independent review

independent review should not treat every source equally.
Use this 3-tier model.

### Tier A — highest trust
Use these as primary evidence.

These are the best sources for direct architectural grounding:
- our own Talos docs and current branch code
- official project docs / official repo docs
- official config / security / evaluation docs

### Tier B — useful but interpret carefully
Use for signal, not blind copying.

These include:
- leak-analysis articles
- reverse-engineering repos
- “collection” repos mirroring leaked code or summarizing it
- community architecture writeups

These are valuable because they reveal hidden mechanisms.
But they also contain hype, selection bias, and product-specific baggage.

### Tier C — design judgment
This includes our conclusions about:
- what Talos should adopt
- what Talos should adapt carefully
- what Talos should avoid

These are not raw facts.
They are architectural decisions filtered through the Talos identity.

independent review must keep these layers separate.

---

## 5. Source inventory — what to give independent review

This section is the practical source pack.

## 5.1 Internal Talos sources (must give independent review)

These are mandatory.

1. `docs/new-architecture/talos-harness-plan.md`
   - current internal harness architecture plan
   - best source for Talos-specific goals, runtime seams, pain points, and rollout order

2. current code from branch `chore/codebase-cleanup-refactor`
   - especially runtime orchestration and tool pipeline classes
   - independent review should inspect these files directly:
     - `AssistantTurnExecutor`
     - `ToolCallLoop`
     - `TurnProcessor`
     - `ConversationManager`
     - `ToolRegistry`
     - `ToolDescriptor`
     - `ContentVerifier`
     - bootstrap wiring

3. this document
   - `docs/new-architecture/talos-harness-source-of-truth.md`
   - use as the alignment and source-evaluation layer

## 5.2 Internal project source files already provided in local sources

4. `alex000kim-article.txt`
   - very useful as a warning source
   - good for understanding product-specific mechanisms in local coding assistant
   - not a source to blindly copy from

5. `Build_a_Multi-Agent_System_(from_Scratch_v2_MEAP.pdf`
   - useful for agent basics, processing loops, trajectory capture, tool abstractions, memory/HITL basics, MCP/A2A concepts
   - educational, not production-gospel
   - use for conceptual structure, not final Talos production choices

## 5.3 External official sources independent review should use

These are the best external high-trust categories.

6. official `openai/codex` docs and config docs
   - good for:
     - approval controls
     - serialized vs parallel MCP tool behavior
     - AGENTS.md / repo-instruction behavior
     - configuration discipline
   - especially useful for trustworthy CLI/runtime mechanics

7. official `google-gemini/gemini-cli` docs
   - good for:
     - approval modes
     - checkpoint / resume ideas
     - config layering
     - trust / workspace / policy thinking
   - use for patterns, not for product mimicry

8. NVIDIA practical security guidance for agentic sandboxing
   - extremely important
   - good for:
     - whole-surface sandboxing
     - blocking network egress
     - blocking writes outside workspace
     - not treating shell alone as the security boundary

9. official SWE-bench docs + current benchmark guidance
   - useful for:
     - evaluation harness discipline
     - reproducible test environments
     - benchmark limitations
   - public benchmark results should never replace Talos’s own private scenario harness

10. OWASP / security guidance for agent memory, skills, or tool ecosystems
   - useful for:
     - memory poisoning risk
     - skill/plugin poisoning risk
     - supply-chain skepticism around agent ecosystems

## 5.4 External reference repos independent review should inspect skeptically

These are useful, but must never be treated as automatic best practice.

11. `chauncygu/collection-external-coding-assistant-source-code`
   - useful for understanding local coding assistant architecture/mechanism discussions
   - strong for harness ideas and product-mechanism visibility
   - weak if used as a copy-paste template

12. `yasasbanukaofficial/external-coding-assistant`
   - similar value to the collection repo
   - useful for reading and cross-checking interpretations of leaked local coding assistant mechanisms
   - not a trustworthy source for what Talos should become by default

13. `ultraworkers/claw-code`
   - most useful part: parity harness / mock harness / deterministic evaluation ideas
   - least useful part for Talos: autonomous multi-agent philosophy

14. `openai/codex`
   - official and higher-trust than mirrors/analysis repos
   - useful source of practical CLI/runtime ideas

15. `google-gemini/gemini-cli`
   - official and higher-trust than commentary
   - useful source of config/approval/trust patterns

---

## 6. What each source is actually good for

This section is critical.
It tells independent review what to extract from each source.

### 6.1 `talos-harness-plan.md`
**Best for:**
- Talos-specific current-state truth
- actual runtime seams
- priority order of harness rollout
- current pain points

**Do not use it for:**
- external validation by itself
- assuming every detail is already correct just because it is ours

### 6.2 Alex Kim article
**Best for:**
- warning signs
- seeing what production agent products really contain around the loop
- identifying anti-patterns and vendor-specific mechanisms
- concrete lessons about:
  - shell hardening depth
  - prompt-cache machinery
  - circuit breakers
  - prompt-only orchestration risks
  - background autonomy / KAIROS dangers

**Do not use it for:**
- copying anti-distillation behavior
- copying undercover mode
- copying DRM/attestation patterns
- copying always-on autonomy

### 6.3 Claw
**Best for:**
- deterministic parity harness ideas
- mock service discipline
- scoreboard/evaluation mindset

**Do not use it for:**
- making Talos multi-agent-first
- making Talos Discord-like or worker-swarm oriented

### 6.4 Codex
**Best for:**
- CLI/runtime discipline
- per-tool approval configuration
- cautious tool execution defaults
- repository instruction handling
- keeping central runtime abstractions clean

**Do not use it for:**
- assuming every product detail maps to local-first Talos constraints

### 6.5 Gemini CLI
**Best for:**
- approval modes
- trust/policy/config layering
- resume/checkpoint thinking
- explicit user-facing operational modes

**Do not use it for:**
- blindly importing product UX or assumptions that are too cloud-product-specific

### 6.6 MEAP agent book
**Best for:**
- conceptual structure
- processing loop mental model
- trajectory capture
- BaseTool / ToolCall / ToolCallResult abstractions
- memory/HITL/MCP/A2A concept clarity

**Do not use it for:**
- deciding Talos production-grade runtime policy by itself
- justifying multi-agent drift for Talos

---

## 7. Hard conclusions we are confident about

These points are strongly supported.

### 7.1 Harness quality matters more than raw model power
This is the central conclusion.
The leap from current Talos to target Talos is mainly execution harness quality.

### 7.2 Talos needs deterministic scenario evaluation
Without this, progress is subjective and regressions are hidden.
This should be the first harness layer.

### 7.3 Talos needs explicit runtime phases
Talos must stop blurring:
- inspect
- plan
- apply
- verify

### 7.4 Talos needs task-level verification
Per-file verification is useful but insufficient.
Talos must know whether the **task** is complete.

### 7.5 Sandboxing must cover the full execution surface
Security cannot stop at shell validation.
All mutating or externally capable operations must obey workspace/policy boundaries.

### 7.6 Approval and trust must be explicit
Talos’s local-first identity depends on predictable approvals, not vague “AI judgment.”

### 7.7 Circuit breakers are mandatory
Any adaptive mechanism can spiral.
Retries, compaction, fallback repair loops, and recovery logic all need hard stop/degrade behavior.

### 7.8 Prompt text is not enough
Critical invariants must live in code, policies, descriptors, or state machines.
Prompt guidance alone is too soft.

---

## 8. Architectural judgments (not pure facts)

These are our reasoned Talos judgments.
independent review should understand them as judgments, not universal truths.

### Adopt directly
These align strongly with Talos:
- deterministic scenario harness
- runtime phase model
- task-level verification harness
- whole-surface sandboxing
- approval/trust models
- strict evaluation mode
- local trajectory/observability capture
- concurrency as opt-in only
- circuit breakers / degradation caps

### Adapt carefully
These may help Talos, but require care:
- memory systems
- checkpoint/resume behavior
- hierarchical project instruction files
- prompt-stability/cache discipline
- richer tool metadata / phase metadata
- benchmark usage beyond private scenarios

### Avoid for Talos
These conflict with Talos identity:
- swarm/multi-agent-first runtime
- background dream/daemon autonomy as core direction
- undercover/identity-masking behavior
- anti-distillation fake-tool mechanisms
- DRM-like client attestation as a current priority
- prompt-only orchestration for critical runtime logic

---

## 9. Known dangers of blind copying

This section should be read by independent review carefully.

### Danger 1 — copying vendor defenses as if they are product quality
Example:
- fake-tool injection
- DRM/attestation
- undercover mode

These may help a vendor defend a product.
They do **not** make Talos more trustworthy.

### Danger 2 — copying multi-agent spectacle instead of bounded competence
Talos is not trying to impress via worker theatrics.
It is trying to become a reliable local operator.

### Danger 3 — copying cloud economics mechanisms without need
Prompt-cache optimization, compaction tricks, and mode latches may make sense in a hosted commercial product.
Talos should only import them when they help **correctness, determinism, or local UX**, not because they look advanced.

### Danger 4 — copying prompt behavior when runtime policy should exist in code
If a mechanism is critical to safety, correctness, or trust, it should not live only in prompt prose.

### Danger 5 — copying educational abstractions straight into production runtime
The book is useful for understanding, but Talos needs stricter production harnessing than a learning framework.

---

## 10. Recommended immediate source pack for independent review

If giving independent review a compact, high-value pack, use this order:

### Mandatory pack
1. `docs/new-architecture/talos-harness-plan.md`
2. `docs/new-architecture/talos-harness-source-of-truth.md`
3. relevant runtime classes from `chore/codebase-cleanup-refactor`
4. `alex000kim-article.txt`

### Strong external pack
5. official Codex docs / config / AGENTS docs
6. official Gemini CLI docs / config docs
7. NVIDIA sandboxing guidance
8. SWE-bench docs / benchmark caveat docs

### Optional secondary pack
9. Claw parity/mock harness docs
10. local coding assistant mirror/collection repos for architectural comparison only
11. MEAP book chapters for conceptual support only

---

## 11. What independent review should be asked to do

independent review should not be asked:
- “copy local coding assistant”
- “make Talos like Claw”
- “make Talos multi-agent”

independent review **should** be asked:
1. validate whether our current harness plan is aligned with Talos identity
2. identify any weak assumptions in our harness rollout order
3. review current runtime seams and map exact insertion points
4. separate hard-evidence practices from design judgment
5. flag any source-derived mechanism that is cloud-specific, deceptive, anti-user, or swarm-biased
6. refine the next implementation slice so it is small, testable, and branch-realistic

---

## 12. Best next implementation move

The best next move remains:

1. build the **scenario harness** first
2. then implement the **runtime phase harness**
3. then add **task-level verification**

Reason:
- scenario harness gives measurement
- phase harness fixes the biggest live usability weakness
- task verifier closes the trust gap

This remains the most grounded path from current Talos to reliable Talos.

---

## 13. Final summary

Talos does not need more hype, more agents, or more product theater.
Talos needs a better harness.

The most useful external sources are the ones that teach:
- deterministic evaluation
- explicit runtime phases
- strong trust and approval boundaries
- whole-surface sandboxing
- circuit breakers
- trajectory visibility
- skepticism about cloud-product baggage

The most important discipline for independent review is this:

> **Do not mistake “present in a famous agent product” for “correct for Talos.”**

That is the core reason this document exists.
