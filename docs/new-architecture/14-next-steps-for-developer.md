# 14. Next Steps for Developer

This document is the practical architecture handoff for development work.

It is written for the developer working from the current codebase.

The goal is to make the next moves clear **without jumping prematurely into full code redesign**.

---

## 1. Read this pack in order

Recommended reading order:
1. `00-executive-summary.md`
2. `01-product-and-scope.md`
3. `02-core-vocabulary.md`
4. `04-system-boundaries.md`
5. `06-workspace-model.md`
6. `07-runtime-shape.md`
7. `09-architecture-decisions.md`
8. `12-v1-scope.md`
9. `13-what-not-to-build-yet.md`

This gives the fastest understanding of the intended project shape.

---

## 2. Preserve what is already strong

The current repo already has valuable foundations.
Do **not** discard them casually.

Preserve and respect:
- local-first behavior
- workspace-scoped indexing
- retrieval discipline
- evidence/citation-oriented answering
- context packing direction
- CLI-first operation
- performance/resource awareness

These are part of the project identity.

---

## 3. The main architectural correction to apply

The biggest architectural correction is this:

### Current tendency
A local RAG CLI is beginning to grow assistant behavior around itself.

### Intended direction
One CLI-first assistant product (**Loqs**) should grow around a clear internal knowledge subsystem (**LOQ-J**).

In practice, this means:
- do not let the knowledge core absorb every new assistant concern
- do not dissolve retrieval/evidence logic into generic runtime code

---

## 4. The most important conceptual move

Adopt **Source** as the root input abstraction.

That means the system should increasingly think in terms of:
- sources
- source type
- format
- media type

rather than only files or documents.

This is what allows the architecture to support:
- coding
- learning
- document work
- later broader source understanding

on one foundation.

---

## 5. What should stay identified as LOQ-J

The following should remain identifiable as the knowledge engine:
- source-to-index preparation
- chunking
- retrieval
- evidence preparation
- context pack assembly
- provenance/citation support
- workspace-scoped knowledge access

Even if module/package names evolve later, this responsibility boundary should remain visible.

---

## 6. What should increasingly become Loqs runtime/platform

The following should be understood as assistant/runtime behavior rather than knowledge-core behavior:
- user-facing CLI orchestration
- task handling
- capability routing
- approval flow
- research mode vs action mode runtime behavior
- workspace operating model
- later action execution and broader assistant workflows

---

## 7. What not to refactor too early

Do **not** start by:
- redesigning every package at once
- building a full persistence layer redesign
- forcing multi-agent structure into the base architecture
- overbuilding memory behavior
- overbuilding action automation
- introducing UI-driven architecture concerns

First keep the architecture boundaries clear.
Then evolve the implementation gradually.

---

## 8. Safe next architectural implementation direction

The safest next implementation direction is:

### Step 1
Preserve current knowledge-engine strengths.

### Step 2
Clarify internal boundaries between:
- knowledge engine behavior
- runtime/orchestration behavior
- CLI/platform surface behavior

### Step 3
Gradually move the project language from:
- file/document-centric

to:
- source/workspace/evidence-centric

### Step 4
Keep V1 focused on:
- source understanding
- retrieval
- grounded summarization/explanation
- coding support
- learning support
- grounded drafting
- coherent CLI runtime

---

## 9. Questions the developer should use as guardrails

Before making a design move, ask:

1. Does this strengthen the workspace model?
2. Does this clarify the source/evidence model?
3. Does this preserve LOQ-J as a distinct knowledge subsystem?
4. Does this keep Loqs understandable as the runtime/assistant shell?
5. Does this help V1 prove real value?
6. Does this avoid premature high-risk complexity?

If not, the move is probably too early or aimed at the wrong layer.

---

## 10. Immediate deliverable mindset

The next development phase should aim for:
- architectural clarity
- minimal conceptual debt increase
- preservation of current strengths
- visible movement toward the Loqs product shape

The developer does **not** need to solve every future problem now.

The developer does need to keep the architecture legible while moving the codebase in the intended direction.

---

## 11. Final handoff statement

The architecture direction is:

**Loqs is the one CLI-first local assistant product. LOQ-J remains inside it as the workspace-scoped knowledge and context engine. Development should preserve the current retrieval/value core while gradually clarifying runtime, workspace, and source boundaries around it.**

That is the developer handoff.
