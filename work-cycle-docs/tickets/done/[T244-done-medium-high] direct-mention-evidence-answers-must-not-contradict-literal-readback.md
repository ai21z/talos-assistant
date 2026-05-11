# T244 - Direct Mention Evidence Answers Must Not Contradict Literal Readback

Status: done

Closed: 2026-05-11

Severity: medium-high

## Problem

T242 fixed read-only evidence non-answers, but a model can still produce a concrete yes/no answer that contradicts the literal file readback.

In the focused audit, GPT-OSS read `docs/summary.md` and answered yes to:

`Read docs/summary.md and tell me if it mentions the private notes marker.`

The file contained `Avoid private notes or secrets`, but it did not contain the phrase `private notes marker`. The model treated related words as evidence for the exact mention question.

## Evidence

Audit:
`local/manual-testing/t239-t242-focused-reaudit-20260511-153616`

Transcript:
- GPT-OSS direct evidence answer: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around the read-only evidence question.
- Final `docs/summary.md` in the GPT-OSS workspace contains no `private notes marker` phrase.

## Scope

- For direct yes/no evidence questions of the shape "does file mention/contain/include/reference X", compare the model's yes/no conclusion against the literal readback search result.
- If the model conclusion contradicts the literal readback, replace it with the deterministic grounded answer.
- Preserve concrete model answers when they agree with the literal readback.
- Do not add broad semantic grading.

## Acceptance

- A model that reads a file and answers yes when the literal term is absent is corrected to a deterministic no.
- A model that answers no when the literal term is absent is preserved.
- A model that answers yes when the literal term is present is preserved.
- Existing non-answer fallback behavior from T242 still works.

## Resolution

- Direct yes/no readback questions now derive the literal answer from the inspected target content.
- If the model gives a yes/no conclusion that contradicts the literal readback, Talos replaces it with the deterministic grounded answer.
- The gate covers both direct `Does file mention X?` prompts and audit-style `Read file and tell me if it mentions X` prompts.
- Agreeing concrete model answers are preserved, so this remains a narrow literal-evidence gate rather than broad semantic grading.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesContradictoryYesAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsAgreeingYesAnswer' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.failedWorkspaceSwitchFencesNextRelativeFolderMutation' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.confirmationAfterWorkspaceFenceAppliesSavedRelativeMutation' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesApologyNonAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsConcreteModelAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionReplacesContradictoryYesAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.readOnlyDirectEvidenceQuestionKeepsAgreeingYesAnswer' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.compoundWorkspaceOperationCanApplyBatchThroughVisibleSurface' --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.hiddenWorkspaceOperationToolIsRejectedBeforeExecution' --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
