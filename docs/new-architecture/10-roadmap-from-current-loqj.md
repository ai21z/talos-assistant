# 10. Roadmap from Current LOQ-J to the Intended Loqs Shape

This document explains how the current LOQ-J codebase can evolve into the intended Loqs architecture.

---

## 1. Current position

The current project behaves like:

**a local RAG CLI that is beginning to grow assistant behavior around itself**

This is a strong starting point.

---

## 2. Target position

The intended future shape is:

**Loqs = the CLI-first local assistant product**
with
**LOQ-J = the internal knowledge and context engine**

This is a one-product, modular-architecture outcome.

---

## 3. Migration principle

The migration should be understood as a **clarification of responsibilities**, not a rewrite of identity from zero.

Preserve the current LOQ-J strengths and move unrelated assistant concerns out of the knowledge core.

---

## 4. Phase 1 — Freeze concepts and boundaries

Stabilize:
- product identity
- vocabulary
- use cases
- storage responsibilities
- workspace model
- runtime shape
- capability map
- architecture decisions

This is what the architecture documents establish.

---

## 5. Phase 2 — Identify three major internal zones

### Zone A — Knowledge engine zone
Future LOQ-J core.
Responsible for turning sources into evidence and context.

### Zone B — Assistant runtime zone
Future Loqs runtime/core.
Responsible for tasks, approvals, and runtime behavior.

### Zone C — CLI/platform surface zone
User-facing command shell and runtime operating surface.

---

## 6. Phase 3 — Reframe the command surface

Move from a "RAG CLI with extra behaviors" toward a "CLI-first assistant with a knowledge subsystem."

---

## 7. Phase 4 — Strengthen the source model

Evolve from file-centric thinking toward source-centric thinking.

That means giving real architectural weight to:
- source
- source type
- format
- media type

---

## 8. Phase 5 — Keep action complexity out of the knowledge core

Prevent the knowledge engine from becoming "the whole assistant."

LOQ-J should not be dominated by:
- workflow routing
- approval orchestration
- broad assistant shell logic
- generalized memory semantics

---

## 9. Phase 6 — Introduce capability bundles on top of the foundations

Build user-visible capabilities on top of the foundations:
- workspace
- source understanding
- knowledge retrieval
- task orchestration
- approval
- artifact generation

This allows coding, learning, research, writing, and later action workflows to grow on the same stable base.

---

## 10. What should be preserved from the current project

Preserve these strengths:
- local-first design
- workspace-scoped indexing
- evidence-driven answers
- retrieval discipline
- CLI-first interaction
- performance/resource awareness

---

## 11. Simplest roadmap summary

### Current
LOQ-J is a strong local RAG CLI with an assistant shell beginning to grow around it.

### Next
Loqs becomes the one CLI-first assistant product.

### Internal structure
LOQ-J remains inside it as the knowledge/context engine.

### Long-term result
One product outside.
Clear subsystems inside.
