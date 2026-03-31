# 18. Accessibility and Organizational Fit

This document defines the architectural stance for accessibility, non-technical adoption, and organizational use.

The goal is to make it explicit that the product is not only for technical users.
It should also be usable by non-technical individuals, teams, businesses, and organizations that need local trust and data protection.

---

## 1. Why this document matters

The project should not be limited to power users who are already comfortable with:
- terminals
- model names
- retrieval concepts
- local runtime details
- advanced configuration

If the architecture only works for technical users, the product remains narrower than it needs to be.

The intended product should be able to serve:
- technical users
- non-technical users
- privacy-conscious individuals
- small businesses
- professional teams
- organizations that want safer local handling of data

This expands the value of the system significantly.

---

## 2. Architectural stance

The architecture should support:
- **powerful local operation for advanced users**
- **simple guided operation for non-technical users**
- **trustworthy local adoption for organizations**

This means the architecture must remain flexible in how the product is operated, explained, and configured.

The product can remain **CLI-first in architecture and current implementation direction** without assuming it will always be **CLI-only for every user type**.

---

## 3. Core accessibility principle

The system should expose complexity progressively.

### For most users
The product should present:
- simple choices
- guided defaults
- understandable workspace behavior
- safe actions with explicit approvals
- recommended local profiles instead of raw technical settings

### For advanced users
The product should still allow:
- deeper control
- explicit profile overrides
- detailed runtime choices
- CLI-native operation
- more transparent system detail

### Architectural implication
The architecture should support both **simple operation** and **expert control** without splitting into two unrelated products.

---

## 4. What this means for model selection

Non-technical users should not have to understand:
- quantization
- context length tradeoffs
- VRAM constraints
- embedding model families
- reranker choices

The architecture should support model selection through:
- **Hardware Profile** detection
- **Model Profile** recommendation
- simple profile names
- clear explanations of tradeoffs in plain language

Examples of user-facing profile language:
- Balanced
- Fast
- Coding Focus
- Vision Enabled
- Low Resource

This is much more accessible than exposing raw model internals as the default experience.

---

## 5. What this means for onboarding

The architecture should allow guided onboarding.

A good future onboarding flow should be able to answer:
- what kind of user is this?
- what kind of machine is this?
- what kind of work do they want to do?
- what local model profile fits them?
- what default workspace types should exist?

This does not need to be fully implemented in V1.

But the architecture should clearly support it.

---

## 6. Workspace accessibility

Workspaces are already one of the strongest accessibility features in the architecture.

Why?
Because non-technical users do not think in terms of:
- index roots
- retrieval boundaries
- context windows

They think in terms of:
- Work
- Personal Admin
- Learning
- Health
- Shopping
- Appointments

That means the workspace model is not only architecturally correct.
It is also one of the best product abstractions for accessibility.

---

## 7. Organizational fit

The architecture should support use by businesses and organizations that care about:
- local processing
- private source handling
- reduced fear of data compromise
- clearer trust boundaries
- controlled action behavior

This does not automatically mean enterprise complexity everywhere.

It means the architecture should already support the foundations organizations care about:
- workspace isolation
- local storage roles
- clear approval boundaries
- explicit trust boundaries for connected systems
- understandable local model/runtime story

---

## 8. Trust for organizations

Organizations will often care less about "AI magic" and more about:
- where data lives
- when data leaves local boundaries
- how workspaces are isolated
- how actions are controlled
- how approvals are handled
- whether the product can be operated safely by non-experts

This means the architecture's local-trust stance is not only a privacy feature.
It is also an adoption feature.

---

## 9. Operating surfaces

The architecture should think in terms of **multiple operating surfaces over one product**.

### Surface A — Expert / CLI surface
For technical and power users.

### Surface B — Guided surface later
For non-technical users, organizational adoption, or assisted setup.

### Important principle
These should be different surfaces over the same architecture, not separate products with different truths.

That means:
- same workspace model
- same source model
- same LOQ-J knowledge engine
- same approval model
- same trust boundaries

This is important for long-term coherence.

---

## 10. V1 stance

V1 can remain CLI-first and still support this broader direction.

How?
By ensuring V1 already has:
- plain language in product concepts
- strong workspace abstractions
- simple profile-oriented thinking
- restrained complexity exposure
- architecture that does not assume all users are engineers

The architecture should avoid boxing the product into a technical-only future.

---

## 11. Final stance

Yes, the project should explicitly target not only technical users, but also non-technical users and organizations that care about local trust and data protection.

Architecturally, this means:
- accessible abstractions
- guided defaults
- progressive complexity exposure
- profile-based model/runtime choices
- strong local trust boundaries
- support for multiple operating surfaces over one coherent core

That added versatility is a strength, and the architecture should support it intentionally.
