# 00. Executive Summary

This document is the short architect brief for the whole project.

It is meant to be readable by:
- product thinking stakeholders
- the project owner
- the lead developer
- future contributors

It summarizes the architecture direction established in the rest of the architecture documents.

---

## 1. What the project is

### User-facing product
**Loqs** is the single user-facing product.

Loqs is a **local-first, CLI-first assistant** designed to help with:
- local knowledge and source understanding
- coding and repository explanation
- learning from selected materials
- grounded summarization and drafting
- careful research and later controlled actions

### Internal subsystem
**LOQ-J** is the internal knowledge and context engine inside Loqs.

LOQ-J is responsible for:
- indexing workspace-scoped sources
- retrieving evidence
- assembling context packs
- preserving provenance/citations

In simple terms:
- **Loqs decides and helps**
- **LOQ-J knows and retrieves**

---

## 2. The main architectural stance

The project should be built as:

**one product outside, clear subsystems inside**

This is not a two-product plan.
It is a one-product, modular-architecture plan.

### Why this matters
We want one assistant experience for the user, but we do not want to collapse:
- knowledge indexing
- retrieval
- context packing
- workflow orchestration
- approvals
- actions
- memory

into one hard-to-understand blob.

---

## 3. The core model

The project is built around the following core concepts:
- **Workspace**
- **Source**
- **Task**
- **Action**
- **Evidence**
- **Context Pack**
- **Artifact**
- **Memory**
- **Approval**

The most important correction in the project model is this:

### The root input abstraction is **Source**, not only "Document"

A source can be:
- a PDF
- a text file
- a code file
- a repository
- a webpage
- an image
- and later other kinds of local or connected content

This matters because coding, learning, document work, and research all depend on source understanding.

---

## 4. Workspaces are central

The project is **workspace-centered**.

A workspace is a local context boundary that groups together:
- sources
- knowledge/index scope
- memory scope
- task history
- approval context
- later policies and capabilities

Without strong workspaces, the system would mix unrelated domains such as:
- work
- personal admin
- coding
- learning
- shopping
- appointments

That would hurt trust and retrieval quality.

---

## 5. What LOQ-J is supposed to be

LOQ-J should remain the **knowledge and context engine**.

Its job is to:
- ingest relevant sources for retrieval
- classify and parse them as needed
- build workspace-scoped knowledge/index state
- retrieve evidence
- prepare context packs
- support provenance-aware answers

LOQ-J should **not** become the whole assistant.

It should remain identifiable as the subsystem responsible for grounded knowledge behavior.

---

## 6. What Loqs is supposed to be

Loqs should be the **CLI-first assistant runtime**.

Its job is to:
- accept user tasks
- understand workspace scope
- call LOQ-J when knowledge is needed
- orchestrate capabilities
- produce artifacts
- ask for approval before sensitive actions

Loqs is the user-facing runtime shell.
LOQ-J is the knowledge engine behind it.

---

## 7. Research mode and action mode are different

The architecture should distinguish:

### Research mode
Read-oriented behavior:
- search
- open
- extract
- summarize
- compare

### Action mode
Execution-oriented behavior:
- fill
- upload
- submit
- confirm
- continue an external workflow

These should not be treated as the same thing.
They have different risk profiles and different approval needs.

---

## 8. Approval is a first-class concept

Approval is not a late safety patch.

It is one of the core runtime concepts.

The system must be able to stop and ask before sensitive work completes.

Examples:
- sending
- uploading
- submitting
- booking
- deleting
- confirming a purchase

This is central to user trust.

---

## 9. Memory is separate from source knowledge

The architecture intentionally separates:
- **source-based knowledge**
- **operational memory**

This matters because indexed sources and remembered preferences/outcomes are not the same kind of truth.

The project should avoid treating memory as a magical replacement for sources.

---

## 10. Storage is hybrid by responsibility

The project should not assume one persistence mechanism for everything.

At a high level, the architecture distinguishes four storage roles:
- raw content storage
- structured state storage
- knowledge index storage
- transient cache storage

This does not choose exact technologies yet.
It only defines truth ownership by role.

---

## 11. What V1 should prove

V1 should prove that a **workspace-centered, CLI-first, evidence-driven local assistant** is genuinely useful.

V1 should focus on:
- workspace-aware source understanding
- LOQ-J knowledge retrieval
- grounded summarization and explanation
- coding support
- learning support
- grounded drafting
- coherent CLI-first runtime behavior

V1 should **not** try to prove everything at once.

---

## 12. What should not dominate too early

The project should not be pulled off-course too early by:
- full browser action automation
- shopping automation as a product center
- appointment automation as a V1 center
- giant generalized memory systems
- multi-agent topology as the foundation
- full local model-management ownership
- UI-first decisions before CLI runtime shape is stable
- premature schema and code-structure cleverness

The project should deepen the foundation before widening the surface.

---

## 13. The roadmap from current repo shape

The current repository already contains:
- strong knowledge-engine behavior
- a growing assistant shell around it

The project does not need a conceptual reset from zero.

Instead, it needs a clarification of responsibilities:
- preserve the strong LOQ-J retrieval/index/value core
- clarify Loqs as the user-facing assistant runtime
- evolve from a mixed local RAG CLI into a CLI-first local assistant platform with a clear internal knowledge engine

---

## 14. Final architect summary

The intended future shape of the project is:

**Loqs is the one CLI-first local assistant product. LOQ-J remains inside it as the workspace-scoped knowledge and context engine. The system is built around workspaces, sources, evidence, tasks, safe actions, artifacts, memory, and approval.**

That is the architecture baseline.
