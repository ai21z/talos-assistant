# 05. Storage Responsibilities

This document defines **storage responsibilities** at a high level.

It does **not** choose final storage products yet.
It does **not** define schemas yet.
It does **not** define Java persistence classes yet.

The goal is to decide **what kind of truth lives where** before implementation choices are made.

---

## 1. Why this document matters

Loqs is not a normal web app.

It is a **local-first assistant platform** that must handle:
- private local sources
- workspace boundaries
- retrieval indexes
- generated artifacts
- memory
- task history
- approvals
- runtime state

Because of that, the project should not assume:
- one database for everything
- one storage abstraction for every kind of data
- one persistence strategy for both raw content and derived state

The right question is:

**What kind of data exists, and what storage role fits it best?**

---

## 2. The four storage roles

### A. Raw Content Storage
For original source content and generated file-based artifacts.

### B. Structured State Storage
For durable structured application state.

### C. Knowledge Index Storage
For LOQ-J retrieval structures.

### D. Transient Cache Storage
For disposable or reconstructable temporary data.

---

## 3. The main architectural rule

The system should separate:
- **source truth**
- **structured operational truth**
- **knowledge index state**
- **temporary cache**

---

## 4. Storage responsibility by core concept

## Workspace
A workspace needs durable structured storage.

## Source
A source has multiple storage aspects:
- raw source content in raw content storage
- metadata in structured state storage
- derived retrieval/index representation in knowledge index storage

## Artifact
Artifacts may be file-based, metadata-only, or mixed.

## Task and Step
Tasks and steps need structured durable storage when we want history and traceability.

## Approval
Approval requests and decisions should be durable structured state.

## Memory
Memory should be durable structured state and remain separate from indexed source content.

## Evidence and Context Pack
Usually derived state; ephemeral, cached, or partially logged when useful.

## Model Profile
Belongs in structured durable state.

---

## 5. Truth ownership summary

### Raw Content Storage owns
- source files
- large generated file artifacts

### Structured State Storage owns
- workspaces
- source metadata
- tasks and steps
- approvals
- memory
- artifact metadata
- model/runtime metadata
- policies and permissions

### Knowledge Index Storage owns
- source-derived retrievable units
- lexical/vector retrieval state
- evidence-oriented retrieval support structures

### Transient Cache Storage owns
- temporary or reconstructable working data

---

## 6. Design rules for storage

### Rule 1 — Do not duplicate large content without clear reason
### Rule 2 — Structured state should remain lightweight
### Rule 3 — Knowledge index state should be rebuildable
### Rule 4 — Temporary state should be disposable
### Rule 5 — Workspace boundaries should be visible in storage responsibilities
### Rule 6 — Safety history should not be ephemeral

---

## 7. Final storage stance

The project should be designed around a **hybrid local persistence model**.

Not because complexity is desirable.

But because the system contains fundamentally different kinds of data, and forcing them all into one persistence model would make the project harder to maintain and less efficient.
