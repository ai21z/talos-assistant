# [T86-done-medium] Read-Target Alias Evidence Loop Stop

Status: done
Priority: medium
Date: 2026-05-02
Closed: 2026-05-02

## Source

This issue was exposed during fresh full TalosBench verification after T83-T85.

The `t57-read-config-requires-evidence` case failed in two related live shapes:

- accepted alias `read_file -> config.json [ok]` was not counted by evidence
  obligation verification, causing a false incomplete-evidence outcome;
- after alias evidence verification was fixed, the model still failed to answer
  and the loop reached the iteration cap after the successful read.

Failing transcript examples:

- `local/manual-testing/talosbench/20260502-131553/t57-read-config-requires-evidence.txt`
- `local/manual-testing/talosbench/20260502-131844/t57-read-config-requires-evidence.txt`

## Problem

Talos accepts tool aliases through `ToolAliasPolicy` and `ToolRegistry`, but
some read-evidence and loop-terminal logic still expected canonical
`talos.read_file` names in `ToolOutcome` records. A successful accepted alias
could therefore execute correctly while downstream policy failed to treat it as
valid evidence.

Separately, a single-target read-only QA turn had no deterministic terminal path
after the required file was read. If the model did not produce final prose, the
loop could continue to the generic iteration cap even though the evidence was
already available.

## Goal

For single-target read-only QA:

- accepted `read_file` aliases count as `talos.read_file` evidence;
- unsupported-format read outcomes through accepted `read_file` aliases still
  dominate as advisory unsupported-document capability results;
- after the required target is read successfully, Talos gives the model a clean
  chance to answer, then can stop and return the gathered evidence if the loop
  starts failing or making no progress;
- if the post-read answer is malformed tool-protocol placeholder text, Talos
  returns the gathered evidence instead of accepting the placeholder as a
  complete answer;
- the existing canonical tool path remains unchanged.

## Non-Goals

- Do not broaden this deterministic answer path to multi-target protected-read
  turns.
- Do not parse arbitrary file formats semantically.
- Do not relax protected-path or approval policy.

## Changes

- Made `EvidenceObligationVerifier` canonicalize accepted tool aliases before
  matching evidence tools.
- Made unsupported-document and protected-read outcome checks canonicalize
  accepted read-file aliases before classifying the outcome.
- Added a `ToolCallRepromptStage` terminal path for `READ_ONLY_QA` turns with
  exactly one expected target after a successful read of that target and a
  later failed/no-progress loop iteration.
- Added answer-shaping fallback for malformed post-read answers after the
  required target has already been read.
- The terminal answer quotes the gathered file evidence rather than inventing a
  semantic summary.

## TDD Evidence

- Added unit coverage that `read_file` alias evidence satisfies
  `READ_TARGET_REQUIRED`.
- Added unit coverage that a single-target read-only QA turn stops after a
  successful `read_file` alias once the loop makes no progress and returns the
  gathered `config.json` content.
- Added unit coverage that malformed placeholder text after read-evidence
  handoff is replaced by the gathered file evidence.
- Updated unsupported-docx outcome coverage so accepted `read_file` alias
  failures remain advisory rather than complete.
- Preserved buffered read-evidence recovery coverage where the model provides a
  normal answer immediately after the handoff read.

## Verification

- `.\gradlew.bat test --tests dev.talos.runtime.policy.EvidenceObligationVerifierTest --no-daemon`
- `.\gradlew.bat test --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest.readOnlyQaStopsAfterSuccessfulNamedReadAliasWhenLoopMakesNoProgress --tests dev.talos.runtime.policy.EvidenceObligationVerifierTest.readTargetAliasSuccessSatisfiesRequiredTarget --tests dev.talos.cli.modes.AssistantTurnExecutorTest.nonProtectedReadTargetNoToolAnswerRunsEvidenceRecovery --tests dev.talos.cli.modes.AssistantTurnExecutorTest.streamingReadEvidencePromptUsesBufferedRecoveryPath --no-daemon`
- `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest.readTargetHandoffReplacesMalformedPostReadAnswerWithEvidence --tests dev.talos.cli.modes.AssistantTurnExecutorTest.nonProtectedReadTargetNoToolAnswerRunsEvidenceRecovery --tests dev.talos.cli.modes.AssistantTurnExecutorTest.streamingReadEvidencePromptUsesBufferedRecoveryPath --tests dev.talos.runtime.toolcall.ToolCallRepromptStageTest.readOnlyQaStopsAfterSuccessfulNamedReadAliasWhenLoopMakesNoProgress --no-daemon`
- `.\gradlew.bat test --tests dev.talos.cli.modes.ExecutionOutcomeTest.unsupportedDocumentReadIsAdvisoryAndTraceOutcomeIsNotComplete --no-daemon`
- `.\gradlew.bat installDist --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId t57-unsupported-docx,t57-read-config-requires-evidence`
- `.\gradlew.bat test --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat`

Latest full TalosBench summary:

- `local/manual-testing/talosbench/20260502-134135/summary.md`
