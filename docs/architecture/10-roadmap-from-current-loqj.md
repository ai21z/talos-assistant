# 10. Roadmap from Current LOQ-J to the Intended Loqs Shape

This document explains how the current LOQ-J codebase can evolve into the intended Loqs architecture.

The goal is not to discuss code details yet.
The goal is to explain the **conceptual migration path**.

---

## 1. Why this roadmap exists

The current repository already contains two different kinds of behavior:

### A. Strong knowledge-engine behavior
Examples:
- indexing
- retrieval
- context packing
- workspace-scoped index handling
- evidence and citation behavior

### B. Assistant-shell behavior
Examples:
- CLI surface
- REPL flow
- mode routing
- runtime/session behavior
- early action-like and web-like concepts

This is not a problem.
It means the project already contains the seeds of the intended architecture.

The roadmap exists to turn that mixed shape into a clearer one.

---

## 2. Current position

### Current state in simple terms
The current project behaves like:

**a local RAG CLI that is beginning to grow assistant behavior around itself**

That is a strong starting point.

### What is valuable already
The current system already shows strong direction in:
- local-first behavior
- workspace-scoped indexing
- retrieval pipeline thinking
- context packing
- CLI-driven usage

Those should be preserved.

---

## 3. Target position

The intended future shape is:

**Loqs = the CLI-first local assistant product**
with
**LOQ-J = the internal knowledge and context engine**

This is a one-product, modular-architecture outcome.

---

## 4. Migration principle

The migration should be understood as a **clarification of responsibilities**, not a rewrite of identity from zero.

The project should not throw away the current LOQ-J strengths.
Instead, it should:
- preserve them
- name them more clearly
- move unrelated assistant concerns out of the knowledge core

---

## 5. Phase 1 — Freeze concepts and boundaries

### Goal
Stabilize the architecture language before implementation restructuring.

### What this phase includes
- product identity
- vocabulary
- use cases
- storage responsibilities
- workspace model
- runtime shape
- capability map
- architecture decisions

### Status
This phase is what the current architecture documents are establishing.

---

## 6. Phase 2 — Identify three major internal zones

The current mixed codebase should gradually be understood as three internal zones.

## Zone A — Knowledge engine zone
This is the future LOQ-J core.

### Main responsibility
Turn sources into evidence and context.

### Contains conceptually
- source-to-index transformation
- retrieval pipeline
- evidence preparation
- context packing
- provenance support

## Zone B — Assistant runtime zone
This is the future Loqs runtime/core.

### Main responsibility
Interpret tasks, route runtime behavior, coordinate approvals and capabilities.

## Zone C — CLI/platform surface zone
This is the user-facing command shell and runtime operating surface.

### Main responsibility
Expose the product clearly through commands and interactive operation.

This three-zone model should guide the next design stage.

---

## 7. Phase 3 — Reframe the command surface

### Goal
Move from a "RAG CLI with extra behaviors" toward a "CLI-first assistant with a knowledge subsystem."

### Important idea
This does not mean removing knowledge-oriented commands.
It means placing them under a clearer product identity.

### Direction
The future command surface should feel like one CLI product with coherent capability groups.

The existing command behavior remains valuable, but its framing should evolve.

---

## 8. Phase 4 — Strengthen the source model

### Goal
Evolve from file-centric thinking toward source-centric thinking.

### Why this matters
The current project is strongest around code/docs retrieval, but the intended architecture needs a more explicit concept of:
- source
- source type
- format
- media type

### Outcome
This will allow the project to grow cleanly into:
- coding support
- learning support
- broader source understanding
- controlled research and action workflows later

---

## 9. Phase 5 — Keep action complexity out of the knowledge core

### Goal
Prevent the knowledge engine from becoming "the whole assistant."

### What this means conceptually
The following should not dominate LOQ-J's identity:
- workflow routing
- approval orchestration
- broad assistant shell logic
- high-level action behavior
- generalized memory semantics

### Outcome
LOQ-J remains a strong subsystem instead of dissolving into a monolith.

---

## 10. Phase 6 — Introduce capability bundles on top of the foundations

### Goal
Add user value without exploding the architecture.

### The right pattern
Build user-visible capabilities on top of the foundations:
- workspace
- source understanding
- knowledge retrieval
- task orchestration
- approval
- artifact generation

### Result
Coding, learning, research, writing, and later action workflows can all grow on the same stable base.

---

## 11. What should be preserved from the current project

The migration should preserve these strengths:
- local-first design
- workspace-scoped indexing
- evidence-driven answers
- retrieval discipline
- CLI-first interaction
- performance/resource awareness

These are not temporary features.
They are part of the product identity.

---

## 12. What should gradually change

The following should gradually become clearer and stronger:
- user-facing identity shifts toward Loqs
- LOQ-J identity becomes explicitly internal and knowledge-focused
- source abstraction becomes first-class
- runtime orchestration becomes explicitly separate from knowledge behavior
- capability bundles are described by architecture, not by accidental package mixing

---

## 13. The simplest roadmap summary

### Current
LOQ-J is a strong local RAG CLI with an assistant shell beginning to grow around it.

### Next
Loqs becomes the one CLI-first assistant product.

### Internal structure
LOQ-J remains inside it as the knowledge/context engine.

### Long-term result
One product outside.
Clear subsystems inside.

---

## 14. Final stance

This roadmap is intentionally conservative.

It does not assume a rewrite.
It does not throw away the current codebase identity.
It does not force implementation choices too early.

It simply provides the conceptual path from:

**current mixed local RAG CLI**

to

**a CLI-first local assistant platform with a clear internal knowledge engine.**
