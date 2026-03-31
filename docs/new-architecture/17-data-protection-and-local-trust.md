# 17. Data Protection and Local Trust

This document defines the architectural stance for data protection and local trust.

The goal is to make privacy and local control explicit architectural concerns, not only product slogans.

---

## 1. Why this document matters

The project's core promise includes:
- local-first operation
- safe use of private sources
- controlled actions
- user trust

If these ideas are not reflected in the architecture, the product promise becomes weak.

---

## 2. Architectural trust stance

The system should be architected so that local trust is supported by design.

That means the architecture should make clear:
- what stays local by default
- what data is treated as sensitive local content
- what boundaries protect context and actions
- when user approval is required
- where later external connectivity would cross trust boundaries

---

## 3. Protected local data

The architecture should assume that all of the following may be sensitive:
- workspace sources
- private documents
- repositories
- notes
- generated artifacts
- memory entries
- approval-sensitive action context
- local runtime/model selections when privacy-sensitive

The system should not assume that only legal or medical documents are sensitive.

Local private work itself is part of the protected domain.

---

## 4. The main trust boundaries

## A. Workspace boundary
The workspace is a trust boundary for context isolation.

## B. Storage-role boundary
Different kinds of truth should live in different storage roles.
This reduces accidental overexposure and improves clarity.

## C. Research mode vs action mode boundary
Read-oriented and execution-oriented behavior should remain distinct.

## D. Approval boundary
Sensitive work should not silently cross from preparation to completion.

## E. Local runtime boundary
When the system is operating with local models and local data, that local execution story should remain understandable.

---

## 5. What should stay local by default

Architecturally, the default assumption should be:
- workspace sources are local
- knowledge index state is local
- structured workspace/task/memory state is local
- generated artifacts are local unless explicitly exported or connected elsewhere later
- model/runtime usage is local when a local profile is selected

This should be the default trust posture.

---

## 6. Approval and trust

Approval is one of the architecture's main trust instruments.

The system should require approval before sensitive transitions such as:
- send
- submit
- upload
- delete
- confirm purchase or booking

This is not only runtime safety.
It is part of data-protection posture.

---

## 7. Data minimization by architecture

The architecture should support data minimization.

Examples:
- do not duplicate large source content without reason
- do not treat temporary extraction state as durable truth by default
- do not blend source content, memory, and temporary runtime data into one undifferentiated store
- do not expand workspace scope implicitly when explicit scope is better

This is a practical privacy and resource principle.

---

## 8. Local trust and model/runtime architecture

The user should be able to understand, at a meaningful level:
- when the assistant is using local models
- when local workspace data is being processed locally
- when a workflow is only preparing work versus completing a sensitive action

The architecture should support this clarity, even if the UI/CLI wording evolves later.

---

## 9. Connected systems and future trust boundaries

The architecture should assume that future integrations may exist.

Examples:
- browser workflows
- email systems
- calendar systems
- external websites

When those arrive, the system should treat them as **trust-boundary crossings**, not as casual extensions of local state.

That means:
- they should be explicit
- they should respect workspace scope
- they should be governed by approval where appropriate

---

## 10. V1 stance on data protection

V1 should make the local-trust architecture visible through:
- workspace-centered design
- local storage roles
- approval-aware runtime flow
- restrained action scope
- clear separation between local knowledge and action execution

V1 does **not** need a giant privacy-management feature system.

It needs architecture that actually supports the privacy promise.

---

## 11. Final stance

Yes, local data protection should be treated as an architectural concern at all levels.

Not by adding vague privacy language everywhere.

But by designing the system so that:
- workspaces isolate context
- storage roles isolate truth types
- approvals protect sensitive transitions
- local model/runtime behavior remains explicit enough to trust
- future connected-system behavior is treated as a boundary crossing, not as default behavior

That is the local-trust architecture stance.
