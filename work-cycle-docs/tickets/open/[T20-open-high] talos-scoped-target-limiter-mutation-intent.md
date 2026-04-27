# [T20-open-high] Ticket: Scoped Target Limiter Mutation Intent
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `work-cycle-docs/tickets/done/[T11-done-high] talos-status-question-verify-only.md`
- `work-cycle-docs/tickets/done/[T14-done-high] talos-repair-followup-after-incomplete-outcome.md`
- `work-cycle-docs/tickets/done/[T16-done-high] talos-web-app-static-verifier-v0.md`
- `work-cycle-docs/tickets/done/[T18-done-medium] talos-web-asset-idempotent-edit-checks.md`

## Why This Ticket Exists

Manual branch review confirmed a known follow-up from T16/T18: Talos still
treats some safe, bounded edit requests as read-only because the request also
contains a negated target.

The key example:

```text
Fix only styles.css. Do not change index.html or scripts.js.
```

This is not a read-only request. It is a scoped mutation request:

- mutation allowed for `styles.css`,
- mutation forbidden for `index.html` and `scripts.js`.

Talos currently loses that distinction.

## Problem

Manual result from installed Talos:

- Prompt:
  - `Fix only styles.css. Do not change index.html or scripts.js.`
- Trace:
  - `contract: DIAGNOSE_ONLY`
  - `mutationAllowed=false`
  - native tools: read-only only
- User-visible behavior:
  - Talos inspected files,
  - hit an iteration limit,
  - then asked the user to provide changes instead of applying the requested
    scoped CSS fix.

Manual evidence:

- `local/manual-testing/branch-review-scope-output.txt`
  - iteration limit around line 16
  - `contract: DIAGNOSE_ONLY` around line 41
  - read-only tool surface around line 43
  - no approval prompt

## Goal

Distinguish global read-only negation from scoped mutation limiters that name
forbidden targets. Talos should preserve mutation intent for safe bounded
requests while keeping forbidden targets explicit and enforceable.

## Scope

In scope:

- Classify scoped limiter prompts as apply-capable when the positive mutation
  request is clear.
- Represent allowed and forbidden target hints in `TaskContract` or an
  adjacent central structure if needed.
- Ensure native tool selection exposes mutating tools for the allowed target.
- Ensure final verification and/or scope guard can detect forbidden-target
  mutations.
- Add deterministic tests for:
  - `Fix only styles.css. Do not change index.html or scripts.js.`
  - `Edit only index.html; don't touch styles.css.`
  - `Do not change anything.` remains read-only.
  - `Diagnose this, do not change files.` remains read-only.

Out of scope:

- Full natural-language policy engine.
- Multi-file permission language beyond simple named target allow/deny hints.
- Browser/runtime validation.
- New shell/browser/MCP tools.

## Architecture Invariant

A negation can limit mutation scope without cancelling mutation intent.

Examples:

```text
Fix only styles.css. Do not change index.html or scripts.js.
```

means:

```text
mutationAllowed = true
allowed target hint = styles.css
forbidden target hints = index.html, scripts.js
```

but:

```text
Do not change anything. Just inspect.
```

means:

```text
mutationAllowed = false
```

## Technical Analysis

Likely root seams:

- `MutationIntent.containsGlobalReadOnlyNegation(...)`
- `MutationIntent.isScopedLimiter(...)`
- `TaskContractResolver.DIAGNOSE_MARKERS`
- `TaskContractResolver.extractExpectedTargets(...)`
- `TaskContract` expected/forbidden target modeling
- `ScopeGuard` and/or `TurnProcessor` if forbidden-target enforcement belongs
  at execution time

Current behavior appears to fail in two ways:

1. `TaskContractResolver.DIAGNOSE_MARKERS` includes `do not change`, so a
   sentence with an otherwise clear positive mutation request can be routed as
   diagnostic/read-only.
2. `MutationIntent.isScopedLimiter(...)` only treats generic phrases like
   `anything else`, `any other`, and `other files` as scoped. It does not treat
   named-file negation as scoped:
   - `Do not change index.html`
   - `Don't touch scripts.js`

The design should not simply remove read-only negations. Talos still needs to
respect `do not change anything`, `do not edit files`, and similar no-mutation
requests. The missing concept is bounded scope, not weaker safety.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/task/TaskContract.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/ScopeGuard.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/runtime/MutationIntentTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`
- `src/test/java/dev/talos/runtime/ScopeGuardTest.java` if present/applicable
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

- Mutation intent:
  - named-file scoped negation keeps mutation intent.
  - global no-mutation language blocks mutation intent.
- Task contract:
  - scoped edit prompt resolves to `FILE_EDIT`, `mutationAllowed=true`.
  - allowed/forbidden target hints are captured if modeled.
- Execution/scope:
  - write/edit to forbidden target is rejected before approval or by scope
    policy if forbidden targets are represented.
  - write/edit to allowed target can reach approval.

E2E:

- Scenario where prompt says:
  - `Fix only styles.css. Do not change index.html or scripts.js.`
  - expected mutating tool surface,
  - expected approval for `styles.css`,
  - expected no mutation of forbidden targets.

Manual:

Installed Talos against a three-file web workspace:

```text
/session clear
/debug trace
Fix only styles.css. Do not change index.html or scripts.js.
```

Expected:

- `contract: FILE_EDIT`
- `mutationAllowed=true`
- native tools include `talos.edit_file`/`talos.write_file`
- approval only for `styles.css`
- no approval for `index.html` or `scripts.js`
- if model attempts forbidden target, the runtime blocks it and reports why.

## Acceptance Criteria

- Scoped target-limiter prompts are apply-capable.
- Pure read-only negation remains read-only.
- Forbidden targets are not silently mutated.
- Trace/tool surface matches the resolved scoped contract.
- Tests cover positive scoped limiter and negative global read-only cases.
- Focused tests, `e2eTest`, `check`, and installed manual verification pass
  before marking done.