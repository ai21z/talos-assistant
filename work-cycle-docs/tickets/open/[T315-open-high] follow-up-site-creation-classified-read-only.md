# T315 - Follow-Up Site Creation Classified Read-Only

Status: fixed in working tree; pending wider regression evidence
Severity: high
Release gate: yes for developer/simple-user beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

A natural follow-up prompt after creating a website-planning text file was classified as read-only:

```text
great! now can you create that site?
```

Talos exposed only read/search/retrieve tools, repeatedly inspected files, and stopped by failure policy instead of entering apply mode.

## Root Cause

`MutationIntent` accepted some conversational prefixes such as `okay`, but did not accept `Great!` as a prefix before an explicit creation request. The mutation parser therefore missed the explicit `can you create that site` request and returned `non-mutating`.

## Fix Direction

Keep the lexical policy conservative, but accept common affirmation prefixes with punctuation before an otherwise explicit mutation request.

## Tests

Added:

- `MutationIntentTest.overwriteRewriteReplaceAndNaturalCreationPhrasingAreExplicitMutationIntent`
- `TaskContractResolverTest.createThatSiteFollowUpAfterSourceFileCreationBecomesApplyCapable`

Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: passed.

## Acceptance Criteria

- `Great! now can you create that site?` is mutation-capable.
- Pure read-only follow-ups remain read-only.
- No advisory or instructional mutation questions become apply-capable.

