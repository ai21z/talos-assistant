# T99 - Tool-Loop Pending Expected And Repair Target Obligation Gate

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: Full Qwen/GPT-OSS audit root-cause review

## Evidence Summary

- Source: full clean two-model audit and follow-up prompt-construction/root-cause review
- Date: 2026-05-03
- Models:
  - Qwen: `ollama/qwen2.5-coder:14b`
  - GPT-OSS: `ollama/gpt-oss:20b`
- Audit root: `local/manual-testing/qwen-gptoss-full-audit-20260503-112017`
- Findings:
  - `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/FINDINGS-FULL-TWO-MODEL.md`
  - `local/manual-testing/qwen-gptoss-full-audit-20260503-112017/PROMPT-CONSTRUCTION-ROOT-CAUSE-RESEARCH.md`

Observed evidence:

- GPT-OSS BMI create received correct expected targets but wrote `script.js`
  instead of required `scripts.js`.
  - `TEST-OUTPUT-GPT-OSS-20B.txt` around lines 1708-1833
- Static verification correctly failed the turn and reported that `script.js`
  does not satisfy `scripts.js`.
- Qwen BMI repair received repair framing but returned no tool calls on repair
  follow-up.
  - `TEST-OUTPUT-QWEN-14B.txt` around lines 1769-2076
- Prompt construction is not the primary failure. Current-turn frames inject
  `[ExpectedTargets]`, `[ExactFileWrite]`, and the `script.js` versus
  `scripts.js` warning.

## Problem

Talos has deterministic action obligations, but after a mutation reprompt the
tool loop can still terminate on a model-controlled no-tool prose response.

The current runtime already continues after partial expected-target progress:

- `ToolCallRepromptStage` detects remaining expected targets and injects
  `[Expected target progress]`.
- `ToolCallRepromptStage` detects remaining full-file repair targets and
  injects `[Static repair progress]`.

However, if the next assistant response contains non-empty prose and no native
or text tool calls, `ToolCallRepromptStage` returns control to the loop and the
next parse exits normally. The pending expected-target or repair-target
obligation is not represented as durable loop state, so the runtime cannot
distinguish a valid end of model-controlled work from an ignored obligation.

This is an action-loop/runtime-control bug, not another prompt wording bug.

## Classification

Primary taxonomy bucket: `TOOL_LOOP_CONTROL`

Secondary buckets:

- `REPAIR_CONTROL`
- `VERIFICATION`
- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level: release-gate follow-up before the next full T61-style audit

Why this level:

Runtime containment is safe after the fact, but milestone audit behavior still
depends on whether the model chooses to obey progress and repair prompts. Talos
should turn ignored pending target obligations into typed deterministic
failures instead of letting no-tool prose become an ordinary loop terminator.

## Goal

Track pending expected-target and static repair-target obligations inside the
tool loop. If a model ignores one of those obligations by returning no tool
calls, the loop must produce a typed deterministic breach that names the source
and targets.

## Scope

- Add a small pending-obligation representation for the tool loop.
- Track pending obligations for:
  - remaining expected mutation targets, such as `scripts.js`;
  - remaining static full-file repair targets from repair context.
- Set the pending obligation when the loop injects an expected-target or static
  repair progress reprompt.
- On the next model response, if the pending obligation exists and the response
  has no executable tool calls, do not allow the response to become a normal
  final answer.
- Record a trace event or action-obligation event naming:
  - breach kind;
  - source;
  - remaining target paths;
  - whether enforcement stopped after the first ignored progress/repair
    obligation.
- Return deterministic failure text that is failure-dominant and includes the
  pending target list.
- Preserve the existing successful path when the model emits the required tool
  calls after the progress reprompt.

## Non-Goals

- No prompt wording changes to `CurrentTurnCapabilityFrame`.
- No new task classification.
- No deterministic static web app generator.
- No provider-level `tool_choice` abstraction in this ticket.
- No Ollama structured `format` or `next_action` fallback in this ticket.
- No OpenAI or Anthropic client plumbing in this ticket.
- No proposal/apply rework.
- No exact literal mismatch taxonomy unless it falls out naturally from the
  same breach structure.

## Acceptance Criteria

- Regression covers wrong-similar-target progress:
  - expected targets include `index.html`, `styles.css`, and `scripts.js`;
  - model successfully mutates `index.html`, `styles.css`, and wrong
    `script.js`;
  - progress reprompt names remaining `scripts.js`;
  - next model response has no tool calls;
  - loop records a typed pending-obligation breach for `scripts.js`.
- Regression covers static repair progress:
  - repair context has remaining full-file targets;
  - progress reprompt names those targets;
  - next model response has no tool calls;
  - loop records a typed pending-obligation breach instead of ordinary
    completion prose.
- Regression proves there is no infinite loop: one ignored pending obligation
  produces one deterministic terminal failure.
- Failure-dominant output contains no success claims such as "complete",
  "ready to use", "open in browser", or manual "save these files" prose.
- Happy path remains unchanged when the model emits required write/edit tool
  calls after the progress reprompt.
- Existing T98 multi-file web create success scenario still passes.

## Suggested Implementation Notes

Likely code areas:

- `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`

Prefer the smallest durable shape:

- A package-private pending obligation record or small controller near the tool
  loop is enough for this ticket.
- Reuse existing target computations:
  - `remainingExpectedMutationTargets(...)`
  - `remainingFullRewriteRepairTargets(...)`
- Keep `[Static verification repair context]` injection where it is today in
  `AssistantTurnExecutor`; this ticket should only gate progress/repair
  continuation after the tool loop has entered the reprompt path.

## Suggested Tests

- `ToolCallLoopTest` or a focused `ToolCallRepromptStage`/obligation test for
  wrong-similar-target breach.
- `ToolCallLoopTest` or e2e scenario for static repair no-tool breach.
- `AssistantTurnExecutorTest` or `ExecutionOutcomeTest` assertion that final
  output is failure-dominant when a pending obligation breach is present.
- Existing happy-path regression:
  - T98 multi-file web create continues until expected targets.
  - Static structural repair continues until planned write targets.

Suggested commands:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

## Audit Follow-Up

Do not run a full T61-style audit for this ticket alone.

After T99 passes normal tests, run a focused clean two-model audit using the
same Qwen/GPT-OSS model pair and prompt-construction probes. Capture full
provider-body JSON for the breach turn and confirm the failure is classified as
a pending-obligation breach rather than generic no-tool prose completion.

## Implementation Result

- Added a small pending action obligation model for tool-loop expected-target
  and static repair progress obligations.
- The loop now records a pending obligation when a mutation-progress reprompt
  names remaining expected targets or remaining static repair full-file
  targets.
- If the next model response has no executable native or text tool calls, the
  loop stops deterministically with a failure decision and failure-dominant
  answer text naming the remaining targets.
- Added trace events:
  - `PENDING_ACTION_OBLIGATION_RAISED`
  - `PENDING_ACTION_OBLIGATION_BREACHED`
- Scoped the gate to mutation progress, so read-only probe flows still use the
  existing mutation retry path.

## Verification

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.staticRepairProgressNoToolProseBecomesDeterministicBreach" --tests "dev.talos.runtime.ToolCallLoopTest.expectedTargetProgressToolCallKeepsHappyPathOpen" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.multiFileWebCreateContinuesUntilExpectedTargets" --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairContinuesUntilPlannedWriteTargets" --tests "dev.talos.harness.JsonScenarioPackTest.structuralWebRepairRedirectsEditFileToWriteFile" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
./gradlew.bat clean test e2eTest installDist --no-daemon
```
