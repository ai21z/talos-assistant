# [Txx-open-priority] Evaluation Finding Title

Status: open
Priority: high | medium | low

## Evidence Summary

- Source: TalosBench | manual prompt | Terminal-Bench | other
- Date:
- Talos version / commit:
- Model/backend:
- Workspace fixture:
- Raw transcript path:
- Trace path or `/last trace` summary:
- File diff summary:
- Approval choices:
- Checkpoint id:
- Verification status:

Redacted prompt sequence:

```text
<prompt sequence with secrets removed>
```

Expected behavior:

```text
<expected contract, tool surface, trace, mutation, verification, and outcome>
```

Observed behavior:

```text
<observed behavior, redacted>
```

## Classification

Primary taxonomy bucket:

- `INTENT_BOUNDARY`
- `CURRENT_TURN_FRAME`
- `TOOL_SURFACE`
- `ACTION_OBLIGATION`
- `PERMISSION`
- `CHECKPOINT`
- `VERIFICATION`
- `OUTCOME_TRUTH`
- `TRACE_REDACTION`
- `REPAIR_CONTROL`
- `MODEL_COMPETENCE`
- `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `<optional>`

Blocker level:

- release blocker
- candidate follow-up
- future milestone
- unsupported

Why this level:

```text
<short justification>
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Fix prompt X.
```

Architectural hypothesis:

```text
<state the runtime boundary, policy owner, verifier, outcome renderer, trace
redaction layer, or capability gap likely responsible>
```

Likely code/document areas:

- `<file or package>`

Why a one-off patch is insufficient:

```text
<explain recurring cluster or invariant>
```

## Goal

```text
<what invariant should hold after this ticket>
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or
  verification policy.
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.

Add ticket-specific non-goals:

- `<non-goal>`

## Implementation Notes

```text
<initial direction; keep deterministic policy ownership clear>
```

## Architecture Metadata

Capability:

- `<capability or none with reason>`

Operation(s):

- `<read/write/edit/mkdir/move/delete/run/verify/etc.>`

Owning package/class:

- `<expected owner>`

New or changed tools:

- `<tool names or none>`

Risk, approval, and protected paths:

- Risk level:
- Approval behavior:
- Protected path behavior:

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior:
- Evidence obligation:
- Verification profile:
- Repair profile:

Outcome and trace:

- Outcome/truth warnings:
- Trace/debug fields:

Refactor scope:

- `<allowed extraction or none>`
- `<explicitly forbidden broad rewrites>`

## Acceptance Criteria

- `<criterion>`
- `<criterion>`
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test:
- Integration/executor test:
- JSON e2e scenario:
- Trace assertion:

Manual/TalosBench rerun:

- Prompt family:
- Workspace fixture:
- Expected trace:
- Expected outcome:

Commands:

```powershell
./gradlew.bat test --no-daemon
```

Add broader commands if runtime code changes:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop unless the ticket explicitly declares a candidate.
- Do not bump version unless this is candidate closeout.
- Do not update `CHANGELOG.md` unless this is candidate closeout.
- Convert live failure evidence into deterministic regression before closeout
  whenever practical.

## Known Risks

- `<risk>`

## Known Follow-Ups

- `<follow-up>`
