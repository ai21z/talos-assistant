# 04. System Boundaries

This document defines the system boundaries at a high level.

The goal is to keep the project understandable and avoid mixing every concern into one large monolith.

---

## 1. One product, clear subsystems

There is **one user-facing product**:
- **Loqs**

Inside that product, there are clear internal responsibilities.

The most important internal subsystem is:
- **LOQ-J** = the knowledge and context engine

This is not a two-product strategy.
It is a one-product, modular-architecture strategy.

---

## 2. What Loqs owns

Loqs owns the assistant/runtime behavior.

### Loqs responsibilities
- user-facing CLI behavior
- task execution and routing
- step-oriented workflows
- workspace interaction model
- research-mode orchestration
- action-mode orchestration
- approval flow
- later: memory policies, browser workflows, action capabilities

### Simple summary
Loqs is responsible for **deciding, coordinating, and helping act**.

---

## 3. What LOQ-J owns

LOQ-J owns the knowledge and evidence behavior.

### LOQ-J responsibilities
- source ingestion for retrieval purposes
- parsing and chunking
- workspace-scoped indexing
- retrieval pipeline
- evidence preparation
- context pack assembly
- provenance/citation support
- knowledge diagnostics and indexing status

### Simple summary
LOQ-J is responsible for **knowing, retrieving, and preparing context**.

---

## 4. Why these responsibilities should remain separate

If everything is blended into one assistant blob, several things become harder:
- testing
- reasoning about quality
- evolving retrieval separately from actions
- keeping the system understandable
- improving knowledge behavior independently from assistant workflows

The separation exists to preserve clarity.

---

## 5. What belongs in shared platform/runtime behavior

Some concerns are not purely Loqs or purely LOQ-J.
They are supporting platform behavior.

Examples:
- configuration loading
- logging/audit basics
- sandbox and safety primitives
- model runtime bindings
- low-level utility concerns

These should remain small and well-defined.
They should not become a dumping ground.

---

## 6. Capability bundles built on top of the core

The following are important product capabilities, but they should not all become separate foundations too early:

- coding support
- learning support
- communication support
- daily briefing
- web research
- appointments
- shopping

These are better understood as **capability bundles built on top of**:
- workspace
- source
- task
- evidence
- actions
- approval

This keeps the architecture simpler.

---

## 7. The core conceptual chain

The core runtime chain should be understood like this:

1. The user works in a **Workspace**
2. The user asks Loqs to perform a **Task**
3. Loqs decides what is needed
4. If local knowledge is needed, Loqs calls **LOQ-J**
5. LOQ-J turns **Sources** into **Evidence** and a **Context Pack**
6. Loqs uses that context to answer or to perform **Actions**
7. Sensitive actions require **Approval**
8. The result becomes an **Artifact**
9. Useful operational context may become **Memory**

This is the most important high-level runtime chain in the project.

---

## 8. What should not be pushed into LOQ-J

The following concerns should not become part of LOQ-J's core identity:
- general assistant shell behavior
- broad workflow routing
- browser action orchestration
- approval policy orchestration
- user-facing multi-domain mode system as the main architecture driver
- generalized memory semantics

LOQ-J should not slowly become "the whole assistant."

---

## 9. What should not be pushed into Loqs Core

The following concerns should not be dissolved into generic runtime code:
- retrieval pipeline quality
- chunking logic
- reranking logic
- evidence packing
- provenance/citation mechanics
- workspace-scoped corpus/index logic

These belong to the knowledge engine and should remain identifiable as such.

---

## 10. Browser boundaries

Browser-related behavior should already be treated as two different kinds of capability.

### Research mode
- search
- open links
- read pages
- extract information
- compare results

### Action mode
- fill forms
- upload files
- click through workflows
- submit or confirm actions

The architecture should not treat them as the same thing.

---

## 11. CLI boundary decision

The project remains **CLI-first**.

That means the command surface should ultimately belong to **Loqs**, while LOQ-J remains the specialized knowledge subsystem behind it.

### Practical implication
The end state is closer to:
- `loqs ...` for the product
- with a knowledge engine inside it

rather than:
- a pure standalone RAG CLI forever

However, retaining a dedicated knowledge-oriented command surface is still valuable inside the CLI-first model.

---

## 12. Boundary decision summary

### Loqs = assistant platform
Owns:
- workflows
- routing
- actions
- approval
- user-facing CLI surface
- workspace operation model

### LOQ-J = knowledge engine
Owns:
- indexing
- retrieval
- evidence
- context packs
- provenance
- source-to-knowledge preparation

### Shared platform layer
Owns:
- configuration
- logging
- safety primitives
- runtime plumbing

This is the intended project shape.
