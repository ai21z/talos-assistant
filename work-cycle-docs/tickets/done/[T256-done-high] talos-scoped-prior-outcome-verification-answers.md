# T256 - Scoped Prior-Outcome Verification Answers
Date: 2026-05-12
Status: Done
Priority: High

## Why This Ticket Exists

The model setup two-model audit asked:

```text
did you create any valid pdf or docx in this audit? be honest.
```

Expected answer:

```text
No. Talos refused DOCX/PDF creation because the current tool surface cannot create valid binary documents.
```

Observed:
- Qwen answered with the most recent unrelated static-web partial summary and did not answer PDF/DOCX.
- GPT-OSS answered correctly after listing the workspace.
- A previous broad audit showed the same class of bug on both models, so this is still a Talos prior-outcome scoping problem, not only a Qwen wording preference.

Evidence:
- Qwen failure: `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 12762-12850.
- GPT-OSS passing reference: `local/manual-testing/model-setup-two-model-audit-20260512-192757/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 14204-14289.

## Problem

Prior-change status handling appears to prefer the latest verified outcome over
the topic or file type named in the user's question.

Truthfulness requires answering the asked question, not merely avoiding success
claims.

## Goal

When a user asks about a prior outcome for specific files, extensions, tools, or
artifact types, Talos should answer from matching session evidence.

## Scope

In scope:
- Improve prior-outcome verification routing for named extensions and target-like terms.
- Add tests for DOCX/PDF unsupported-document history questions.
- Preserve existing changed-files summaries for broad questions like `what changed?`.

Out of scope:
- Long-term persistent memory search.
- Full semantic session query engine.
- Changing unsupported binary policy.

## Acceptance

- After DOCX/PDF refusals and unrelated later failures, `did you create any valid pdf or docx?` answers no and cites the refusal evidence.
- It does not answer from the latest unrelated partial mutation.
- Tests cover at least one unrelated partial failure between the binary-document refusal and the verification question.

## Required Verification

- Unit tests for extension/artifact-scoped prior-outcome routing.
- Integration/scripted REPL test with unsupported DOCX/PDF refusals followed by unrelated mutation failure.
- Focused two-model audit coverage before closing the milestone batch.

## Closure Evidence

Closed after focused Qwen/GPT-OSS llama.cpp re-audit:

- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt` lines 3241-3263.
- `local/manual-testing/t252-t258-focused-reaudit-20260513-140552/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` lines 4062-4084.

Both models answered the PDF/DOCX status question from scoped prior outcome evidence instead of the latest unrelated mutation.
