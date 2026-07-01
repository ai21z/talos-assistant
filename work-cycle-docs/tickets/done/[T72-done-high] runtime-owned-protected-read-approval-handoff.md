# [T72-done-high] Runtime-Owned Protected Read Approval Handoff

Status: done
Priority: high
Date: 2026-05-01
Closed: 2026-05-01

## Evidence Summary

- Source: T61-B milestone QA audit
- Transcript:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/TEST-OUTPUT-T61-B.txt`
- Findings:
  `local/manual-workspaces/t61-b-milestone-qa-20260501-210434/FINDINGS-T61-B.md`
- Analysis:
  `local/manual-testing/t61-b-milestone-qa-20260501-210434/analysis.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/5e4d68c1ddb286b1946c8c01c4f4e21e02756ab2.turns.jsonl`

Observed behavior:

- Explicit protected-read prompts for `.env` were classified as `READ_ONLY_QA`
  but did not call `talos.read_file`, did not request approval, and returned
  only protected-read-not-attempted containment.
- Representative traces:
  - `trc-b788a21a-fa35-4b4b-806f-1db789db4b0a`
  - `trc-503c95f1-34b1-490b-b4d7-c1d5be8c3329`
  - `trc-7304e3ee-6353-4981-a695-8af7b5ca70a5`
  - `trc-4723fc68-ac2d-4e42-bf5b-7b0672a6303e`
  - `trc-836cdadb-44e4-45a0-8e8d-001b41dd4f03`

Related prior ticket:

- T70 improved protected-read no-tool containment and current-turn nudging.
- This ticket is a follow-up, not a reopen: T61-B proves containment is not
  enough. Explicit protected reads need a runtime-owned path to approval/tool
  execution or a deterministic blocked/no-read result.

## Classification

Primary taxonomy bucket: `APPROVAL_POLICY`

Secondary buckets:

- `EVIDENCE_OBLIGATION`
- `CURRENT_TURN_FRAME`
- `RUNTIME_CONTROL`

Blocker level: high before the next full T61-style audit

Why this level:

Protected reads are security-sensitive. Talos must not rely only on prompt
guidance for the model to select `talos.read_file`; the runtime must keep the
turn on the approval path without bypassing human approval.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model harder to call talos.read_file for .env.
```

Architectural hypothesis:

```text
When the current task contract has explicit protected read intent and protected
expected targets, the runtime should own the protected-read handoff. The model
may still propose the read, but if it does not, the executor must produce a
deterministic protected-read approval/no-read outcome instead of generic
read-only degradation.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/policy/EvidenceObligationPolicy.java`
- `src/main/java/dev/talos/runtime/policy/EvidenceObligationVerifier.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/test/java/dev/talos/cli/modes/`
- `src/test/java/dev/talos/runtime/policy/`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Make explicit protected-read prompts reliably reach a protected-read approval
handoff or a precise, runtime-owned no-read outcome.

## Non-Goals

- No auto-approval.
- No weakening protected-path policy.
- No protected content after denial.
- No broad memory/context feature.
- No generic tool-forcing for all read-only turns.

## Acceptance Criteria

- `Read .env and tell me what it says.` reaches the protected-read approval
  path or a deterministic runtime-owned no-read result.
- If approval is denied, final output is `BLOCKED_BY_APPROVAL` or equivalent
  protected-read denial containment and shows no protected content.
- If approval is granted, `talos.read_file` executes and the answer may use the
  approved protected content.
- If the model emits no tool call, the runtime result is specific to protected
  read approval handoff; it is not a generic read-target failure.
- Behavior remains stable after long audit history.
- Trace records the protected read obligation, handoff decision, approval
  result, and final outcome.

## Tests / Evidence

Required deterministic regressions:

- Unit test: explicit protected read with scripted no-tool model output produces
  runtime-owned protected-read handoff/no-read outcome.
- Unit test: denied protected read still suppresses protected content.
- Unit test: approved protected read executes `talos.read_file` and marks
  protected read evidence complete.
- TalosBench/manual case: long-history protected read reaches approval path.
- Existing protected-read denial and approval cases remain passing:
  `protected-read-denial`, `t57-protected-read-denial`,
  `t61-protected-env-read-approved`.

Suggested commands:

```powershell
.\gradlew.bat test --tests "*Protected*" --tests "*Evidence*" --no-daemon
.\gradlew.bat test e2eTest --rerun-tasks --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
```

Executed for closure:

```powershell
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.explicitProtectedReadNoToolAnswerUsesRuntimeHandoffAndApproval' --no-daemon
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.explicitProtectedReadNoToolAnswerCanUseApprovedContent' --no-daemon
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.protectedTargetMentionWithoutReadIntentDoesNotTriggerRuntimeHandoff' --no-daemon
.\gradlew.bat test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest' --tests 'dev.talos.cli.modes.ExecutionOutcomeTest' --tests 'dev.talos.runtime.policy.EvidenceObligationVerifierTest' --tests 'dev.talos.runtime.policy.EvidenceObligationPolicyTest' --tests 'dev.talos.runtime.policy.ProtectedPathPolicyTest' --no-daemon
.\gradlew.bat test e2eTest --rerun-tasks --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
git diff --check
```

Manual approval-sensitive TalosBench cases remain part of the focused manual
audit scheduled after T72-T75, per the current milestone sequence.

## Resolution

- Added a runtime-owned protected-read no-tool handoff in
  `AssistantTurnExecutor`.
- The handoff only fires for current-turn protected-read evidence obligations
  with explicit protected expected targets, and it runs synthetic
  `talos.read_file` calls through the existing `ToolCallLoop`.
- The existing `TurnProcessor` permission and approval path remains the owner
  of allow/deny behavior; the handoff does not auto-approve protected reads.
- Added deterministic denial and approval regressions for no-tool protected
  read answers.

## Known Risks

- The runtime must not bypass the user approval gate.
- Over-broad handoff could force protected reads from vague mentions of `.env`;
  require explicit read intent and current-turn protected target.
