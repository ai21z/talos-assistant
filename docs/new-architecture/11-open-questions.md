# 11. Open Questions

This document captures the most important open questions that remain after the current architecture foundation.

The goal is to make uncertainty visible without blocking progress.

---

## 1. Workspace questions

### WQ-01 — Is a workspace only logical, or also file-system anchored?
### WQ-02 — Can one source belong to multiple workspaces?
### WQ-03 — How explicit should cross-workspace behavior be?

---

## 2. Source questions

### SQ-01 — Should sources be referenced in place or imported?
### SQ-02 — What source types are required in V1 versus later?
### SQ-03 — How much source-type-specific behavior belongs in the core versus later capability layers?

---

## 3. Knowledge / LOQ-J questions

### KQ-01 — What is the minimum strong source model needed for LOQ-J evolution?
### KQ-02 — How much derived knowledge state should be durable versus rebuildable?
### KQ-03 — What provenance detail should be treated as mandatory in V1?

---

## 4. Memory questions

### MQ-01 — What counts as memory versus source-derived knowledge?
### MQ-02 — What should be remembered automatically versus explicitly?
### MQ-03 — Should memory be workspace-only by default, with global memory as a special case?

---

## 5. Approval questions

### AQ-01 — What actions are always approval-gated?
### AQ-02 — Can users configure approval strictness by workspace?
### AQ-03 — What should be retained as durable approval history?

---

## 6. Runtime questions

### RQ-01 — How much user-facing mode language is actually helpful?
### RQ-02 — What should be a direct command versus an interactive workflow?
### RQ-03 — How much runtime history should be visible by default?

---

## 7. Research and action questions

### RAQ-01 — What exact behaviors belong to research mode in V1?
### RAQ-02 — Which action workflows are too risky for early implementation?
### RAQ-03 — What is the safe earliest action use case?

---

## 8. Model/runtime questions

### MRQ-01 — How much model management belongs in V1?
### MRQ-02 — How much should the runtime assume existing local model backends versus owning them directly?

---

## 9. Product identity questions

### PIQ-01 — How quickly should the user-facing identity move from LOQ-J to Loqs?
### PIQ-02 — Should a dedicated knowledge-oriented command surface remain visible under the Loqs CLI?

---

## 10. How to use this document

This document exists to:
- capture real open questions
- avoid pretending all design uncertainty is resolved
- help the project make deliberate decisions later

The architecture is already stable enough to guide the next phase.
