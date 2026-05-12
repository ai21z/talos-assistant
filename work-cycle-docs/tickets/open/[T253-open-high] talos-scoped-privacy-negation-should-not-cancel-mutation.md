# T253 - Scoped Privacy Negation Should Not Cancel Mutation
Date: 2026-05-12
Status: Open
Priority: High

## Why This Ticket Exists

The model setup two-model audit found that this normal user request was
classified as read-only:

```text
summarize long-notes.txt into ideas/summary.md. keep it tight. don't touch private files.
```

Talos should create `ideas/summary.md` from `long-notes.txt` while avoiding
private/protected files. Instead it set:

```text
Contract: READ_ONLY_QA mutationAllowed=false verificationRequired=false
Classification reason: global-read-only-negation
Expected targets: ideas/summary.md
```

Evidence:
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 3987-4087.
- `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 4046-4135.

## Problem

`MutationIntent` treats `don't touch` as a global read-only negation even when
the phrase scopes privacy or protected files:

```text
don't touch private files
do not read protected files
don't mutate secrets
```

This cancels a clear source-to-target mutation request.

## Goal

Explicit mutation requests must survive scoped privacy/safety clauses.

Talos should distinguish:

```text
Summarize long-notes.txt into ideas/summary.md. Don't touch private files.
```

from:

```text
Inspect long-notes.txt. Don't touch any files.
```

## Scope

In scope:
- Refine `MutationIntent.containsGlobalReadOnlyNegation(...)` and scoped limiter handling.
- Add `TaskContractResolver` tests for source-to-target artifact requests with privacy clauses.
- Verify expected targets and source evidence targets remain correct.
- Ensure protected files are still not read without approval.

Out of scope:
- Broad natural-language classifier rewrite.
- New planner.
- Weakening true no-mutation prompts.

## Acceptance

- `summarize long-notes.txt into ideas/summary.md. don't touch private files` resolves to mutation allowed.
- Expected targets contain only `ideas/summary.md`.
- Source evidence targets contain `long-notes.txt`.
- Protected/private file text is not added as an expected or source target unless explicitly requested.
- Focused tests cover both positive scoped mutation and negative true read-only prompts.

## Required Verification

- Unit tests for scoped negation classification.
- Integration/scripted REPL test proving the summary file is written and protected/private files are not read.
- Focused two-model audit coverage before closing the milestone batch.
