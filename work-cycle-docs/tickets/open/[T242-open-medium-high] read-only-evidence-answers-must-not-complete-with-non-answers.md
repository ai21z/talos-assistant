# T242 - Read-Only Evidence Answers Must Not Complete With Non-Answers

Severity: medium-high

## Problem

A read-only question can gather the required file evidence and still complete with a non-answer.

In the audit, Qwen read `docs/summary.md` after being asked whether it mentions the private notes marker, but answered only:

`I apologize for the confusion. Let's proceed with the task as originally requested.`

The turn was marked complete.

## Evidence

Audit:
`local/manual-testing/user-perspective-broad-reaudit-20260511-143729/FINDINGS-USER-PERSPECTIVE-BROAD-REAUDIT.md`

Transcript:
- User prompt: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2726`
- Non-answer: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2750`
- Turn marked complete: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2755`
- Tool result contained the file content: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3207`

## Scope

- Add a minimal quality gate for read-only evidence answers where the user asks a direct evidence question.
- Detect obvious non-answers:
  - apology-only,
  - task restatement,
  - "let's proceed" with no answer,
  - no yes/no answer for direct yes/no prompts.
- Retry once with compact evidence or return a deterministic "answer not grounded" failure.
- Do not add broad semantic grading or expensive model self-critique.

## Acceptance

- A scripted model that reads the required file but returns an apology/task restatement does not complete successfully.
- A scripted model that reads the file and answers the direct question completes.
- Existing ordinary read-only workspace explanations still work.
- Targeted tests and full Gradle tests pass.

