# 06. Workspace Model

This document defines how workspaces should be understood in the project.

The goal is to keep workspaces simple, central, and practical.

---

## 1. Why workspaces are central

Workspaces are one of the most important concepts in Loqs.

Without workspaces, the system becomes:
- noisy
- hard to trust
- harder to search accurately
- more likely to mix unrelated context

Examples of context that should not be mixed casually:
- work documents
- personal admin
- coding projects
- learning material
- shopping tasks
- appointment flows

This is why the system is **workspace-centered**.

---

## 2. What a workspace is

A **Workspace** is a local operating boundary for context.

A workspace groups together:
- sources
- knowledge/index scope
- memory scope
- task history
- approval context
- later: policies, allowed tools, site permissions, preferred models

In simple terms:

**A workspace is the local place where one coherent kind of work happens.**

---

## 3. What a workspace is not

A workspace is not only:
- a folder
- a repository
- an index
- a conversation
- a session

A workspace may reference folders or repositories, but it is broader than that.

A workspace is a **context boundary**, not only a file-system concept.

---

## 4. Examples of workspaces

Examples:
- ADP Work
- Loqs / Architecture
- Personal Admin Barcelona
- Learning Docker
- Health Admin
- Shopping
- Appointment Booking
- Macroverse

The exact names matter less than the principle:

**different worlds should be allowed to stay separate.**

---

## 5. What belongs to a workspace

At the conceptual level, a workspace can contain or govern:

### A. Sources
Examples:
- local files
- repositories
- notes
- saved webpages later
- imported artifacts

### B. Knowledge scope
LOQ-J indexing and retrieval should be scoped to the workspace when appropriate.

### C. Memory scope
A workspace should have its own memory context.

### D. Task history
Tasks performed in the workspace belong to that workspace.

### E. Approval scope
Approval-sensitive actions should be understandable in workspace context.

### F. Policy scope later
Examples:
- allowed capabilities
- allowed websites
- browser mode restrictions
- output preferences

---

## 6. Global context vs workspace context vs session context

The system should distinguish three levels of context.

## A. Global context
Things that apply across the whole user environment.

Examples:
- language preference
- general writing style preference
- default safety preferences
- default runtime preferences

## B. Workspace context
Things that apply inside one workspace.

Examples:
- attached sources
- workspace memory
- task history
- domain vocabulary
- source scope
- local policies

## C. Session context
Things that apply only to the current interaction or run.

Examples:
- current question
- current step
- currently retrieved evidence
- temporary selections
- temporary browser/session state

### Why this distinction matters
Without it, the system will mix:
- permanent truth
- workspace truth
- temporary execution state

That leads to confusion and bad architecture.

---

## 7. Workspace behavior rules

### Rule 1 — Retrieval should respect workspace scope by default
When a task asks about local knowledge, the workspace is the first retrieval boundary.

### Rule 2 — Memory should be workspace-aware
Useful remembered context should not leak freely across unrelated workspaces.

### Rule 3 — Sensitive action policy should be understandable in workspace terms
A shopping action and a work action should not feel like the same trust zone.

### Rule 4 — Workspaces should support both focused and broad usage
A workspace may be very narrow or fairly broad, as long as its context is coherent.

### Rule 5 — Cross-workspace behavior should be explicit
If the system later supports cross-workspace search or briefing, it should be intentional and visible.

---

## 8. Workspace and LOQ-J

LOQ-J should treat the workspace as a key boundary.

That means LOQ-J should be able to work with:
- workspace-scoped source selection
- workspace-scoped indexing
- workspace-scoped retrieval
- workspace-scoped diagnostics/status

This is already one of the strongest directions in the current system and should remain true.

---

## 9. Workspace and actions

The workspace should also influence action behavior.

Examples:
- research workspace → read-oriented browser behavior
- shopping workspace → action behavior with stronger approval expectations
- coding workspace → repository-aware understanding and file-safe behavior
- appointment workspace → form and document preparation behavior

This does not mean each workspace needs a different architecture.

It means the workspace provides the context boundary in which policies make sense.

---

## 10. Workspace lifecycle questions

These questions will matter later, but the concept should already allow for them:
- how a workspace is created
- how sources are attached or referenced
- whether sources are imported or linked in place
- whether one source can be associated with more than one workspace
- how cross-workspace search works later

We do not need the final answers yet.

What matters now is that the workspace abstraction is strong enough to support them.

---

## 11. Simple conceptual model

The simplest accurate mental model is:

**A workspace is a local context boundary where sources, knowledge, memory, tasks, and policies stay coherent.**

That sentence should guide later design.

---

## 12. Architectural consequence

Because workspaces are central:
- the CLI should be workspace-aware
- LOQ-J should be workspace-aware
- memory should be workspace-aware
- action flows should understand workspace scope
- storage responsibilities should reflect workspace boundaries

This makes the project more understandable and more trustworthy.
