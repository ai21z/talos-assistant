# T218 - First Static Repair Writes Must Reject Empty Or Placeholder Payloads Before Apply

Severity: high

## Problem

The T217 focused audit showed a remaining static repair containment gap. Qwen received correct static repair context, then wrote empty full-file replacements for `styles.css` and `scripts.js` on the first repair iteration. Talos applied both empty writes and only reported the empty files after mutation.

T215 already blocks empty or placeholder writes under a pending static repair obligation, but that protection only covers continuation/reprompt repair progress. The first repair-turn write is still allowed through approval/apply.

## Scope

- When a `[Static verification repair context]` names `Full-file replacement targets`, reject `talos.write_file` calls for those targets before approval/apply if the replacement content is missing, blank, empty, or literal template-placeholder content.
- Apply this to the first static repair iteration, not only pending repair continuations.
- Record a deterministic trace event with a machine-readable failure kind.
- Preserve valid non-empty full-file repair writes.

## Evidence

- `local/manual-testing/llama-cpp-t217-static-selector-repair-guard-re-audit-20260508-040639/FINDINGS-LLAMA-CPP-T217-STATIC-SELECTOR-REPAIR-GUARD-RE-AUDIT.md`
- Qwen T217 focused audit: repair wrote empty `styles.css` and `scripts.js`; final workspace check reported both files as `bytes=0`.

## Acceptance

- Focused test covers first static repair iteration writing empty `styles.css` and proves the file is unchanged.
- Rejection happens before approval and before tool execution.
- Failure output is failure-dominant and contains no success/manual browser prose.
- Trace includes `ACTION_OBLIGATION_EVALUATED` with failure kind `STATIC_REPAIR_INVALID_WRITE_CONTENT`.
- Existing pending static repair invalid-write tests still pass.
- Full Gradle build/install passes.
