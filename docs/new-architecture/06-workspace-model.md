# 06. Workspace Model

This document defines how workspaces should be understood in the project.

---

## 1. Why workspaces are central

Workspaces are one of the most important concepts in Loqs.

Without workspaces, the system becomes:
- noisy
- hard to trust
- harder to search accurately
- more likely to mix unrelated context

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

---

## 5. What belongs to a workspace

A workspace can contain or govern:
- sources
- knowledge scope
- memory scope
- task history
- approval scope
- policy scope later

---

## 6. Global context vs workspace context vs session context

### A. Global context
Things that apply across the whole user environment.

### B. Workspace context
Things that apply inside one workspace.

### C. Session context
Things that apply only to the current interaction or run.

This distinction prevents mixing permanent truth, workspace truth, and temporary execution state.

---

## 7. Workspace behavior rules

### Rule 1 — Retrieval should respect workspace scope by default
### Rule 2 — Memory should be workspace-aware
### Rule 3 — Sensitive action policy should be understandable in workspace terms
### Rule 4 — Workspaces should support both focused and broad usage
### Rule 5 — Cross-workspace behavior should be explicit

---

## 8. Workspace and LOQ-J

LOQ-J should treat the workspace as a key boundary.

That means LOQ-J should support:
- workspace-scoped source selection
- workspace-scoped indexing
- workspace-scoped retrieval
- workspace-scoped diagnostics/status

---

## 9. Workspace and actions

The workspace should also influence action behavior.

Examples:
- research workspace → read-oriented browser behavior
- shopping workspace → action behavior with stronger approval expectations
- coding workspace → repository-aware understanding and file-safe behavior
- appointment workspace → form and document preparation behavior

---

## 10. Workspace lifecycle questions

Important later questions include:
- how a workspace is created
- how sources are attached or referenced
- whether sources are imported or linked in place
- whether one source can be associated with more than one workspace
- how cross-workspace search works later

---

## 11. Simple conceptual model

**A workspace is a local context boundary where sources, knowledge, memory, tasks, and policies stay coherent.**

---

## 12. Architectural consequence

Because workspaces are central:
- the CLI should be workspace-aware
- LOQ-J should be workspace-aware
- memory should be workspace-aware
- action flows should understand workspace scope
- storage responsibilities should reflect workspace boundaries
