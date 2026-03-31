# 15. Next Architectural Steps

This document defines the next architectural steps after the current foundation pack.

The goal is to show what should happen next in architecture work, in the right order, without jumping straight into code or premature infrastructure detail.

---

## 1. Why this document exists

The current architecture pack establishes:
- product identity
- vocabulary
- boundaries
- storage responsibilities
- workspace model
- runtime shape
- capability map
- V1 scope

That is the foundation.

The next phase should now make the architecture more actionable for implementation.

---

## 2. Step order

The recommended next architecture sequence is:

### Step 1 — Define the V1 source support matrix
Clarify exactly which source types and formats are in V1.

Examples:
- plain text
- markdown
- code files
- repositories
- PDFs
- later: DOCX, email, spreadsheets, images

### Step 2 — Define the target internal module map
Turn the current conceptual boundaries into a target module view.

At a high level, this should clarify:
- Loqs runtime/platform zone
- LOQ-J knowledge zone
- shared platform/support zone
- capability execution zone

### Step 3 — Define the local runtime and model-selection architecture
Clarify:
- where model choice happens
- how model profiles are selected
- how hardware awareness is used
- what belongs to V1 versus later

### Step 4 — Define the local trust and data-protection architecture
Clarify:
- what stays local by default
- what counts as protected local data
- how action/risk boundaries affect data handling
- how workspaces, storage roles, and approvals support trust

### Step 5 — Define the first implementation-facing architecture views
Produce a small set of practical views such as:
- runtime sequence view
- storage responsibility view
- module interaction view

### Step 6 — Define the first implementation roadmap
Translate architecture into a phased delivery plan for the current repo.

---

## 3. What should come before code restructuring

Before major restructuring, the project should define:
- V1 source matrix
- target module map
- local runtime/model strategy
- local trust/data-protection strategy

These are the most valuable missing pieces between the current architecture baseline and safe implementation planning.

---

## 4. What should not happen yet

Do not jump immediately into:
- full schema design
- complete package rewrites
- framework-heavy refactors
- advanced multi-agent decomposition
- broad action automation architecture

The next phase should still focus on **clarification**, not explosion of detail.

---

## 5. Expected output of the next phase

After the next architecture phase, the project should have:
- a precise V1 source scope
- a target internal module structure
- an explicit local model/runtime choice story
- an explicit hardware-awareness story
- an explicit data-protection story
- a clearer handoff for implementation planning

---

## 6. Final stance

Yes, the project should document next architectural steps.

The foundation is now strong enough that the next architecture work should move from:
- concept stabilization

to:
- implementation-facing clarification

without yet collapsing into code-first design.
