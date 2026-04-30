# [T56-open-high] ConversationBoundaryPolicy And READ_ONLY_QA Shrink

Status: open
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed failures:

- `Hello friend` classified as `READ_ONLY_QA`, exposed read/search tools, and
  inspected/searched the workspace.
- `how are you are you good?` classified as `READ_ONLY_QA` and exposed tools.
- `perfect just as I want it!` classified as `READ_ONLY_QA` and exposed tools.
- Slash-command-like text such as `debug /trace` fell into model handling.

## Classification

Primary taxonomy bucket: `INTENT_BOUNDARY`

Secondary buckets:

- `TOOL_SURFACE`
- `ACTION_OBLIGATION`
- `TRACE_REDACTION`

Blocker level: release blocker

Why this level:

Talos cannot be shown as a general local assistant if ordinary conversation can
expose workspace read/search tools.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add "Hello friend" to small talk phrases.
```

Architectural hypothesis:

```text
Conversation and command-boundary handling needs a deterministic policy before
workspace QA fallback. READ_ONLY_QA should stop meaning casual chat,
acknowledgement, command typo, list-only, explicit read, protected read, and
artifact-create miss all at once.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/task/TaskType.java`
- `src/main/java/dev/talos/runtime/policy/ActionObligationPolicy.java`
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java`
- `src/main/java/dev/talos/runtime/toolcall/NativeToolSpecPolicy.java`
- `src/main/java/dev/talos/cli/repl/slash/CommandRegistry.java`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Introduce deterministic conversation and command boundaries so no-workspace
turns have direct-answer-only obligations and no visible workspace tools.

## Non-Goals

- No LLM classifier.
- No evidence-obligation implementation beyond making explicit read cases ready
  for T57.
- No active task context.
- No broad artifact profile system.
- No phrase-only patch as the final design.

## Implementation Notes

- Add `ConversationBoundaryPolicy` or equivalent focused class.
- Detect at least greetings, acknowledgements, gratitude, closure, capability
  chat, privacy/no-workspace chat, and command typo/near-command phrases.
- Make these boundaries feed `CurrentTurnPlan` after T55.
- Keep real workspace questions routed to inspection.
- Ensure `NativeToolSpecPolicy` exposes no tools for direct-answer-only turns.
- Keep `/debug`, `/last trace`, and valid slash commands in slash routing.
- Treat command typo/near-command handling as direct answer or command-help
  guidance, not workspace QA.

## Acceptance Criteria

- `Hello friend` resolves to no-workspace direct answer with no visible tools.
- `how are you are you good?` resolves to no-workspace direct answer with no
  visible tools.
- `perfect just as I want it!` resolves to acknowledgement/direct answer with no
  visible tools.
- Privacy/no-workspace prompts still suppress tools.
- Capability chat remains deterministic and does not inspect workspace.
- Real workspace questions still expose the appropriate read-only tools.
- Near-slash-command typos do not enter `READ_ONLY_QA`.
- No regressions to list-only and mutation-capable turns.

## Tests / Evidence

Required deterministic regression:

- Unit test: conversation boundary cases produce direct-answer-only obligation.
- Unit test: workspace-intent greetings still inspect.
- Unit test: command typo or near-command phrase does not expose read/search
  tools.
- Tool surface test: direct-answer-only turns have no native tools.
- TalosBench cases for T54 small talk and command typo prompt families.

Manual/TalosBench rerun:

- Prompt family: `Hello friend`, `how are you are you good?`,
  `perfect just as I want it!`, `debug /trace`.
- Workspace fixture: include `notes.md` with hidden token.
- Expected trace: no tools, action obligation `DIRECT_ANSWER_ONLY`.
- Expected outcome: no workspace content leak and zero tool calls.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

## Known Risks

- Over-broad chat detection could suppress real workspace requests.
- Command typo handling must not invent command execution behavior.

## Known Follow-Ups

- T57 makes explicit read and protected read obligations first-class.
- T61 converts the full T54 prompt family into TalosBench gates.
