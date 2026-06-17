---
paths:
  - "src/main/java/**/*.java"
---

# Java source conventions (Talos)

Full doctrine: `AGENTS.md` sections Engineering Standards, Implementation Rules, Policy And Runtime Ownership, Before Changing Code.

- Senior Java 21 design judgment. SOLID and patterns are tools, not religion. Prefer simple, explicit, testable designs over abstract architecture cosplay.
- Make the smallest coherent change. Preserve unrelated work and existing behavior unless the task asks to change it. No speculative cleanup while implementing a focused ticket.
- Favor explicit names and strong types. Avoid hidden global state, speculative abstractions, and broad "manager" classes with unclear ownership. Keep side effects visible and controllable. Prefer deterministic flows where safety matters.
- Keep policy logic in dedicated owners, not scattered conditionals (task intent, tool-surface selection, path classification, permission, protocol sanitization, verification, repair, outcome rendering, trace capture/redaction, checkpoint, command profile).
- `AssistantTurnExecutor` is an orchestrator, not a warehouse for every marker, retry rule, cleanup phrase, and final-answer patch.
- Add or update tests when behavior changes. Review the diff before declaring done. Keep public APIs stable unless changing them is necessary.
- NEVER weaken the trust-surface invariants from any code path: approval, permission, checkpoint, trace redaction, privacy/model-context withholding, or outcome-truth. Fail closed.
