# [T67-done-medium] Model Switch Command Boundary And Small-Talk Classification

Status: done
Priority: medium
Date: 2026-05-01
Completed: 2026-05-01

## Evidence Summary

- Source: T61 manual audit
- Transcript: `local/manual-workspaces/t61-audit-20260501-110306/TEST-OUTPUT-T61.txt`
- Related tickets:
  - `work-cycle-docs/tickets/done/[T56-done-high] conversation-boundary-policy-and-read-only-qa-shrink.md`
  - `work-cycle-docs/tickets/open/[T63-open-low] debug-command-level-alias-ergonomics.md`

Observed behavior:

- `/model` returns `Unknown command`; the actual discover/list command is
  `/models`, and switching uses `/set model <backend/model>`.
- After `/set model ollama/gemma4:26b-a4b-it-q4_K_M`, the next prompt
  `Hello friend, how are you?` is conversational and uses no tools, but the live
  Prompt Audit classifies it as `READ_ONLY_QA`, exposes read-only workspace
  tools, and records `activeTaskContext{state=EXPIRED}`.
- The audit did not capture a dedicated `/last trace` immediately after this
  model-switch small-talk turn; the evidence is the live Prompt Audit printed
  before the next prompt.

Important line references:

- `/model` unknown and `/models` guidance:
  `TEST-OUTPUT-T61.txt:1635-1650`
- `/set model ...` and following small-talk Prompt Audit:
  `TEST-OUTPUT-T61.txt:1652-1675`

## Classification

Primary taxonomy bucket: `INTENT_BOUNDARY`

Secondary buckets:

- `CLI_UX`
- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level: medium follow-up

Why this level:

The response did not call tools and did not leak workspace content, so this is
not a release-blocking privacy failure. But it shows T56 small-talk shrinking
can regress under long history/model-switch conditions, and the command UX
confused the audit flow.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Only add /model as an alias and ignore the misclassification.
```

Architectural hypothesis:

```text
Slash-command turns should be a hard conversation boundary for following
intent classification. Model switching should not leave expired active context
or workspace-visible read-only tool framing attached to a pure small-talk turn.
```

Likely code/document areas:

- `src/main/java/dev/talos/cli/repl/slash/`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/runtime/turn/`
- `src/e2eTest/resources/scenarios/`
- `tools/manual-eval/talosbench-cases.json`
- `tools/manual-eval/README.md`

## Goal

Make model-switch command UX clear and preserve T56 small-talk/no-tool
classification immediately after model command turns.

## Resolution

- `/model` now aliases `/models`, so the command used during the T61 audit is
  accepted rather than reported as unknown.
- `/help` now lists the model command flow, and `/help models` / `/help model`
  explicitly documents `/models`, `/model`, and `/set model <backend/model>`.
- The exact audit prompt `Hello friend, how are you?` is classified as
  `SMALL_TALK` and uses `DIRECT_ANSWER_ONLY` with no native or prompt tools.
- Expired active task context is cleared for pure small-talk boundary turns
  instead of rendering `activeTaskContext{state=EXPIRED}` into the prompt audit.
- The TalosBench model-switch regression case is now owned by T67 as
  `t67-model-switch-small-talk`.

## Non-Goals

- No new model provider.
- No model installation manager.
- No broad slash-command natural-language parser.
- No change to debug command ergonomics beyond links to T63 if needed.

## Acceptance Criteria

- `/model` either aliases `/models` or returns guidance that directly names
  `/models` and `/set model <backend/model>`.
- `/models` help and `/help` make the model-switch flow discoverable.
- After `/set model ...`, a prompt such as `Hello friend, how are you?` is
  classified as `SMALL_TALK`, has no visible workspace tools, and records
  `DIRECT_ANSWER_ONLY`.
- Expired active context does not cause workspace tool visibility for pure
  small-talk after slash commands.
- TalosBench has a deterministic or manual-gated case that captures `/last
  trace` immediately after model-switch small talk.

## Tests / Evidence

Required deterministic regression:

- Slash command test for `/model` alias or explicit guidance.
- Task classification test for small talk after a model-switch command/history
  boundary.
- TalosBench/manual case rerun that captures `/last trace` immediately after
  the model-switch small-talk prompt.

Suggested commands:

```powershell
.\gradlew.bat test --no-daemon
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Executed evidence:

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` - pass,
  validated 25 cases.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` - pass.
- `.\gradlew.bat test e2eTest --no-daemon` - pass.
- `.\gradlew.bat clean installDist --no-daemon` followed by
  `pwsh .\tools\install-windows.ps1 -Force -Quiet` - pass.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -CaseId
  t67-model-switch-small-talk -IncludeManualRequired` - pass.

Focused manual evidence:

- Summary:
  `local/manual-testing/talosbench/20260501-131552/summary.md`
- Transcript:
  `local/manual-testing/talosbench/20260501-131552/t67-model-switch-small-talk.txt`
- Observed `/last trace`: `SMALL_TALK`, `nativeTools: none`,
  `promptTools: none`, `actionObligation: DIRECT_ANSWER_ONLY`,
  `activeTaskContext: NONE_OR_NOT_DERIVED`, and `Tool calls: 0`.

## Known Risks

- Model switch is a command, not a workspace task. Fixing this at the wrong
  layer could suppress legitimate context for ordinary non-command follow-ups.
  Keep the boundary specific to slash-command turns.
