# Talos Tickets

Ticket files are split by lifecycle:

- `open/` contains open and in-progress tickets.
- `done/` contains completed tickets.
- `new-work.md` stays at this root as architecture doctrine, not as an active
  ticket.

When a ticket is completed, update its filename and body status, then move it
from `open/` to `done/`.

Future tool and capability tickets must include the Architecture Metadata
section from `templates/evaluation-finding-ticket-template.md`. At minimum,
they must state capability ownership, operation type, risk, approval behavior,
protected path behavior, checkpoint behavior, evidence obligation, verification
profile, repair profile, outcome/trace changes, and allowed refactor scope.
