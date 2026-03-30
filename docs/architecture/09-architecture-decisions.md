# 09. Architecture Decisions

This document records the key architecture decisions that shape the project.

These are not low-level implementation choices.
They are project-shaping decisions that should guide later development.

---

## AD-01 — One user-facing product, not two separate products

### Decision
The user-facing product is **Loqs**.

### Explanation
We do not want two unrelated tools competing for identity.
The user should experience one assistant product.

### Consequence
- user-facing command surface should eventually center on `loqs`
- LOQ-J remains as an internal subsystem, not necessarily a separate end-user product

---

## AD-02 — LOQ-J remains a distinct knowledge/context subsystem

### Decision
LOQ-J remains a clear internal subsystem inside Loqs.

### Explanation
Knowledge indexing, retrieval, evidence preparation, context packing, and provenance are specialized concerns that should remain independently understandable.

### Consequence
The knowledge engine should not disappear into generic runtime code.

---

## AD-03 — The project is CLI-first

### Decision
The command line is a first-class operating surface.

### Explanation
The CLI is not a temporary developer convenience.
It is part of the intended user experience.

### Consequence
- runtime design must support direct commands and interactive flow
- architecture documents should assume CLI-first operation

---

## AD-04 — The system is workspace-centered

### Decision
Workspace is a central architectural concept.

### Explanation
The system needs isolated operating boundaries for context, retrieval, memory, and policies.

### Consequence
- retrieval should be workspace-aware by default
- memory should be workspace-aware by default
- actions should understand workspace policy context

---

## AD-05 — Source is the root input abstraction

### Decision
The project is modeled around **Sources**, not only "documents".

### Explanation
Many user capabilities depend on reading different kinds of input:
- PDFs
- code files
- repositories
- webpages
- images
- emails later

### Consequence
The architecture should support source type, format, and media type as meaningful distinctions.

---

## AD-06 — Coding and learning are capability bundles, not separate architectural worlds

### Decision
Coding support and learning support are first-class user capabilities, but they are built on the same source/evidence foundation.

### Explanation
This keeps the architecture simpler and prevents fragmentation.

### Consequence
Coding and learning should reuse:
- workspace
- source understanding
- knowledge retrieval
- task orchestration
- artifact generation

---

## AD-07 — Research mode and action mode are different

### Decision
The architecture must distinguish read-oriented research behavior from execution-oriented action behavior.

### Explanation
These have different risk profiles, expectations, and safety needs.

### Consequence
The runtime and capabilities should not blur these together.

---

## AD-08 — Approval is a core runtime concept

### Decision
Approval is not optional glue added later.
It is a first-class runtime concept.

### Explanation
Trust depends on explicit review and confirmation before sensitive work completes.

### Consequence
Approval behavior must influence later runtime and storage design.

---

## AD-09 — Memory is separate from indexed source knowledge

### Decision
Memory is not the same thing as source retrieval.

### Explanation
Indexed sources and operational memory serve different purposes.

### Consequence
They should remain separate concerns in architecture and later persistence design.

---

## AD-10 — Persistence is hybrid by role, not single-mechanism by default

### Decision
The system should be designed around multiple storage roles.

### Explanation
Raw content, structured state, knowledge index state, and transient cache are not the same kind of data.

### Consequence
The project should not prematurely assume one persistence mechanism for everything.

---

## AD-11 — Architecture must stay understandable

### Decision
The architecture should favor understandable boundaries over cleverness.

### Explanation
The project must remain readable by both developers and non-architect collaborators.

### Consequence
We avoid premature abstraction layers, unnecessary complexity, and implementation-led conceptual design.

---

## AD-12 — Multi-agent is not the primary architectural driver

### Decision
The project should not be modeled primarily around multi-agent ideas at this stage.

### Explanation
Multi-agent behavior may become useful later, but it should not dominate the foundational model.

### Consequence
The base architecture should make sense even as a single orchestrated assistant runtime.

---

## Summary

These decisions define the intended project shape:

- one product
- CLI-first
- workspace-centered
- source-based
- knowledge-backed through LOQ-J
- safe and approval-aware
- modular and understandable

These decisions should be treated as the current architectural baseline.
