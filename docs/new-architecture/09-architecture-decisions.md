# 09. Architecture Decisions

This document records the key architecture decisions that shape the project.

---

## AD-01 — One user-facing product, not two separate products
The user-facing product is **Loqs**.

## AD-02 — LOQ-J remains a distinct knowledge/context subsystem
LOQ-J remains a clear internal subsystem inside Loqs.

## AD-03 — The project is CLI-first
The command line is a first-class operating surface.

## AD-04 — The system is workspace-centered
Workspace is a central architectural concept.

## AD-05 — Source is the root input abstraction
The project is modeled around **Sources**, not only documents.

## AD-06 — Coding and learning are capability bundles, not separate architectural worlds
They are built on the same source/evidence foundation.

## AD-07 — Research mode and action mode are different
These have different risk profiles and should remain distinct.

## AD-08 — Approval is a core runtime concept
Approval is first-class, not optional glue added later.

## AD-09 — Memory is separate from indexed source knowledge
Memory and source retrieval serve different purposes.

## AD-10 — Persistence is hybrid by role, not single-mechanism by default
Raw content, structured state, knowledge index state, and transient cache are different storage roles.

## AD-11 — Architecture must stay understandable
The architecture should favor understandable boundaries over cleverness.

## AD-12 — Multi-agent is not the primary architectural driver
The project should make sense as a single orchestrated assistant runtime first.

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
