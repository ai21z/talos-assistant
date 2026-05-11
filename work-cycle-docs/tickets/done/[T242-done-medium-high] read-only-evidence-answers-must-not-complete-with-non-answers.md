# T242 - Read-Only Evidence Answers Must Not Complete With Non-Answers

Status: done

Closed: 2026-05-11

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

## Resolution

- Read-target answer shaping now detects obvious apology/task-restatement non-answers.
- Direct yes/no evidence questions now require a yes/no-style conclusion after the target file is read.
- If the model read the required target but failed to answer, Talos derives a narrow deterministic answer from the inspected readback for simple direct evidence questions such as "Does file.md mention X?"
- Concrete model answers are preserved.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesApologyNonAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsConcreteModelAnswer' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesApologyNonAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsConcreteModelAnswer' --tests dev.talos.runtime.policy.EvidenceObligationVerifierTest --tests dev.talos.runtime.policy.EvidenceObligationPolicyTest --tests dev.talos.runtime.policy.EvidenceGateTest --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
