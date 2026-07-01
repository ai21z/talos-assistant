# [T53-done-high] Add evaluation failure intake workflow

Status: done
Priority: high

## Context

T49 created the TalosBench live prompt matrix and taxonomy. T50 added a manual
runner, T51 added trace assertions, and T52 documented Terminal-Bench 2 as
external evaluation pressure rather than a current release gate.

The next step is a disciplined intake workflow so prompt and benchmark failures
become architecture-level tickets instead of one-off prompt patches.

## Goal

Create an evaluation failure intake workflow and a reusable ticket template for
manual prompts, TalosBench runs, and benchmark findings.

## Non-Goals

- No runtime behavior changes.
- No TalosBench runner changes.
- No Terminal-Bench integration.
- No shell/browser/MCP/multi-agent behavior.
- No version bump.
- No `CHANGELOG.md` update.
- No implementation ticket for a specific failure cluster.

## Implementation Notes

Create:

- `docs/evaluation/03-failure-intake-and-ticketing.md`
- `work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md`

The workflow should cover:

- recording failure evidence
- classifying failures with the TalosBench taxonomy
- choosing blocker level
- requiring an architectural hypothesis
- requiring deterministic and manual regression paths
- requiring non-goals
- using a reusable ticket template

## Acceptance Criteria

- Failure intake doc exists at
  `docs/evaluation/03-failure-intake-and-ticketing.md`.
- Ticket template exists at
  `work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md`.
- The process requires recording:
  - prompt
  - workspace
  - model
  - transcript
  - trace
  - expected behavior
  - observed behavior
- The process uses the TalosBench taxonomy:
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
- The process defines blocker levels:
  - release blocker
  - candidate follow-up
  - future milestone
  - unsupported
- The process requires an architectural hypothesis and rejects prompt-only
  framing.
- The process requires a regression path:
  - unit test
  - e2e scenario
  - manual prompt family
  - trace assertion
- The process requires non-goals that prevent scope creep.
- No runtime source changes.
- `./gradlew.bat test --no-daemon` passes.

## Tests / Evidence

Completed:

- `./gradlew.bat test --no-daemon` - PASS

## Work-Test Cycle Notes

Use the inner dev loop. This ticket does not declare a versioned candidate and
does not update `CHANGELOG.md`.

## Known Risks

- Intake can become bureaucracy if it is too heavy for small findings. Keep it
  focused on evidence, classification, and regression path.
- Tickets still need human review to avoid duplicate work and over-broad
  milestone scope.

## Implementation Summary

- Added `docs/evaluation/03-failure-intake-and-ticketing.md`.
- Added reusable template
  `work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md`.
- Documented the required failure evidence fields: prompt, workspace, model,
  transcript, trace, expected behavior, observed behavior, file diffs,
  approval, checkpoint, and verification status.
- Documented blocker levels: release blocker, candidate follow-up, future
  milestone, and unsupported.
- Required architectural hypotheses so findings are framed as runtime,
  policy, verifier, trace, or outcome boundaries rather than prompt-specific
  patches.
- Required deterministic and manual regression paths.
- Added default non-goals to prevent shell/browser/MCP expansion, LLM
  classifiers for safety-critical policy, phrase dumps without ownership, and
  bypassing approval/permission/checkpoint/trace/verification.

## Known Follow-Ups

- Use the template for future TalosBench and Terminal-Bench findings.
- Consider a later lightweight index of evaluation-derived tickets if the
  findings volume grows.
