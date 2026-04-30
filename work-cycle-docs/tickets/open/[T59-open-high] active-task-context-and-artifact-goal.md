# [T59-open-high] ActiveTaskContext And ArtifactGoal

Status: open
Priority: high

## Evidence Summary

- Source: T54 prompt audit re-evaluation
- Date: 2026-04-30
- Raw transcript path: `local/manual-workspaces/t54-audit-20260430-105839/TEST-OUTPUT-T54.txt`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed failures:

- `Please propose a better README... Do not edit yet` followed by `make those
  changes` relied on model reconstruction from conversation history.
- Broad workspace reads happened where a structured active task continuation
  should have carried target and proposed operation.
- Follow-ups after denial, partial mutation, or verification failure are
  represented mostly in prose.

## Classification

Primary taxonomy bucket: `CURRENT_TURN_FRAME`

Secondary buckets:

- `INTENT_BOUNDARY`
- `VERIFICATION`
- `REPAIR_CONTROL`
- `OUTCOME_TRUTH`

Blocker level: high follow-up after T55 through T58

Why this level:

Active task context is important for real sessions, but it is safer to build
after immutable turn state, conversation boundaries, evidence obligations, and
outcome dominance exist.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Make "make those changes" work.
```

Architectural hypothesis:

```text
Talos needs small structured session state for the active task and artifact
goal. This state should carry targets, proposed operation, verifier findings,
previous denial/partial status, and proposed edit summaries across follow-ups
without making raw chat history the only source of continuity.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/Session.java`
- `src/main/java/dev/talos/runtime/SessionData.java`
- `src/main/java/dev/talos/runtime/JsonSessionStore.java`
- `src/main/java/dev/talos/cli/repl/SessionMemory.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/trace/LocalTurnTrace.java`

## Goal

Persist conservative active task context and artifact goal state so natural
follow-ups can inherit the right target, operation, evidence, and verification
context without broad guessing.

## Non-Goals

- No long-term semantic memory system.
- No automatic mutation from vague follow-ups unless prior context and current
  user approval semantics make it safe.
- No dynamic capability registry.
- No model-authored context that can override deterministic policy.

## Implementation Notes

- Add `ActiveTaskContext` with current targets, proposed operation, previous
  outcome status, verifier findings, denied/blocked state, and expiration or
  clearing rules.
- Add `ArtifactGoal` with artifact kind, operation, target set, and verifier
  profile placeholder.
- Update context after propose-only turns, verified mutations, failed verifier
  turns, and denied mutations.
- Suppress or clear context for privacy/no-workspace and unrelated new tasks.
- Render context summary in prompt audit.
- Keep the first version explicit and small.

## Acceptance Criteria

- Proposal followed by `make those changes` carries target and proposed edit
  summary into the new turn plan.
- Follow-up after static verification failure can reference previous verifier
  findings without broad workspace guessing.
- Follow-up after approval denial knows no files changed.
- No-workspace chat suppresses active task context.
- New unrelated explicit requests do not inherit stale active context.
- Prompt audit shows active context presence, suppression, or absence.

## Tests / Evidence

Required deterministic regression:

- Unit test: active context update after propose-only answer.
- Unit test: active context suppression for no-workspace turns.
- Unit test: unrelated explicit target clears or ignores previous context.
- Executor/e2e test: propose README changes, then apply them.
- TalosBench case: proposal plus follow-up.

Manual/TalosBench rerun:

- Prompt family: propose README changes, then `make those changes`.
- Expected trace: active context present and bounded to README.
- Expected outcome: mutation or approval flow targets the proposed file.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Known Risks

- Stale context can be worse than no context. Clearing and suppression rules are
  part of the ticket, not follow-up polish.
- Capturing proposed edit text can expose sensitive content in traces. Keep
  trace summaries redacted and compact.

## Known Follow-Ups

- Capability profile work can own richer artifact-specific goal details.
