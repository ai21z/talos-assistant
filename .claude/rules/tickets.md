---
paths:
  - "work-cycle-docs/tickets/**"
---

# Ticket conventions (Talos)

Full doctrine: `AGENTS.md` section Ticket And Regression Discipline. Lifecycle detail: `work-cycle-docs/tickets/README.md` and `work-cycle-docs/tickets/open/README.md`. Template: `work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md`.

- Tickets live in `work-cycle-docs/tickets/open/` and `work-cycle-docs/tickets/done/`. The directory must match the status token in the filename.
- Filename form: `[Txxx-<status>-<prio>] short-slug.md`, where status is `open`, `in-progress`, or `done`, and prio is `high`, `medium`, or `low`. The body carries a matching `Status:` header.
- For ticket ID >= 739 the strict filename and Status rules are enforced by `TicketHygieneTest`. IDs must be unique across `open/` and `done/`. Older tickets are grandfathered and vary in format.
- On close: verify acceptance criteria from code, tests, audit evidence, and final state. Then rename `[Txxx-open-*]` (or `-in-progress-`) to `[Txxx-done-*]`, update the body Status, and move the file to `done/`. Do not close a ticket because behavior "looks better."
- Every confirmed failure, implementation batch, audit gate, or release blocker maps to a ticket. Do not leave a finding only in `reports/`.
- Deferred tickets stay in `open/` only when the body says `deferred-beyond-beta` or equivalent.
