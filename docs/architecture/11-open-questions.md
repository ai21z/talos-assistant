# 11. Open Questions

This document captures the most important open questions that remain after the current architecture foundation.

These questions are intentionally kept at the architectural and product level.
They are not implementation tasks yet.

The goal is to make uncertainty visible without blocking progress.

---

## 1. Workspace questions

### WQ-01 — Is a workspace only logical, or also file-system anchored?
A workspace is more than a folder, but we still need to decide how strongly it is tied to one or more local paths.

### WQ-02 — Can one source belong to multiple workspaces?
This affects:
- source ownership
- duplication policy
- indexing policy
- memory and approval context

### WQ-03 — How explicit should cross-workspace behavior be?
Examples:
- cross-workspace search
- cross-workspace briefing
- explicit multi-workspace tasks

The architecture currently assumes cross-workspace behavior should be explicit rather than implicit.

---

## 2. Source questions

### SQ-01 — Should sources be referenced in place or imported?
This affects:
- storage responsibilities
- duplication behavior
- update detection
- user expectations

### SQ-02 — What source types are required in V1 versus later?
The architecture supports a broad source model, but V1 needs a smaller concrete subset.

### SQ-03 — How much source-type-specific behavior belongs in the core versus later capability layers?
This affects simplicity and future growth.

---

## 3. Knowledge / LOQ-J questions

### KQ-01 — What is the minimum strong source model needed for LOQ-J evolution?
We already know source is the right root abstraction, but the minimum practical internal shape is still open.

### KQ-02 — How much derived knowledge state should be durable versus rebuildable?
This affects later persistence design and operational strategy.

### KQ-03 — What provenance detail should be treated as mandatory in V1?
We know evidence and provenance matter, but the exact minimum useful level is still open.

---

## 4. Memory questions

### MQ-01 — What counts as memory versus source-derived knowledge?
This boundary is conceptually clear, but later policy will need practical rules.

### MQ-02 — What should be remembered automatically versus explicitly?
This affects user trust and runtime simplicity.

### MQ-03 — Should memory be workspace-only by default, with global memory as a special case?
The current architecture leans that way, but it is still an open design question.

---

## 5. Approval questions

### AQ-01 — What actions are always approval-gated?
We already know approval is first-class, but the later policy matrix still needs definition.

### AQ-02 — Can users configure approval strictness by workspace?
This may be powerful, but could add early complexity.

### AQ-03 — What should be retained as durable approval history?
This affects later structured state design.

---

## 6. Runtime questions

### RQ-01 — How much user-facing mode language is actually helpful?
We know the runtime should be capability-driven rather than gimmick-driven, but final CLI surface design still needs refinement.

### RQ-02 — What should be a direct command versus an interactive workflow?
This affects CLI ergonomics.

### RQ-03 — How much runtime history should be visible by default?
This affects traceability, usability, and simplicity.

---

## 7. Research and action questions

### RAQ-01 — What exact behaviors belong to research mode in V1?
Research is clearly different from action mode, but the minimum V1 research feature set still needs tighter definition.

### RAQ-02 — Which action workflows are too risky for early implementation?
This is partly answered in scope documents, but should remain explicit.

### RAQ-03 — What is the safe earliest action use case?
This will matter when moving from architecture to phased delivery.

---

## 8. Model/runtime questions

### MRQ-01 — How much model management belongs in V1?
The architecture recognizes model profiles, but V1 likely should not overinvest in full model management.

### MRQ-02 — How much should the runtime assume existing local model backends versus owning them directly?
This is a later implementation decision, but architecturally important.

---

## 9. Product identity questions

### PIQ-01 — How quickly should the user-facing identity move from LOQ-J to Loqs?
Architecturally the answer is clear, but rollout strategy is still open.

### PIQ-02 — Should a dedicated knowledge-oriented command surface remain visible under the Loqs CLI?
The architecture suggests yes, but final UX language is still open.

---

## 10. How to use this document

This document should not freeze progress.

It exists to:
- capture real open questions
- avoid pretending all design uncertainty is resolved
- help the project make deliberate decisions later

The presence of open questions does **not** mean the architecture is blocked.

The architecture is already stable enough to guide the next phase.
