# [T49-done-high] Design TalosBench live prompt matrix

Status: done
Priority: high

## Context

T48 added a current-turn capability frame and action-obligation checks after a
live qwen prompt showed Talos correctly exposing write tools while the model
still claimed it could not modify files.

That kind of issue is best found by installed Talos live prompting, but the
results need structure. Talos needs an evaluation layer that turns live prompt
failures into architecture buckets and deterministic regressions instead of
one-off prompt patches.

## Goal

Design TalosBench v1: a manual/live prompt evaluation matrix and failure
taxonomy for installed Talos and local models.

TalosBench should evaluate whether Talos behaves as a safe, local, truthful
workspace operator, with clear release-gating rules and a path from live
failure to architectural ticket to deterministic regression.

## Non-Goals

- No runtime behavior changes.
- No prompt runner implementation in this ticket.
- No Terminal-Bench integration.
- No version bump.
- No `CHANGELOG.md` update.
- No shell, browser, MCP, or multi-agent work.

## Implementation Notes

Create `docs/evaluation/01-talosbench-live-prompt-matrix.md` with:

- purpose and scope
- failure taxonomy
- prompt families and negative controls
- scoring rules
- trace requirements
- release gating
- Terminal-Bench relationship
- work-test-cycle intake process

Keep the design concrete enough for follow-up runner and trace-assertion
tickets, but do not implement those in T49.

## Acceptance Criteria

- `docs/evaluation/01-talosbench-live-prompt-matrix.md` exists.
- The doc defines TalosBench as a live/manual evaluation layer for safe,
  local, truthful workspace operation.
- The doc covers capability/onboarding, privacy, data minimization, directory
  listing, workspace explanation, mutation, protected read/write, approval,
  checkpoint/restore, literal verification, repair, status follow-up, trace
  redaction, and unsupported capability honesty.
- The doc defines the required taxonomy buckets:
  `INTENT_BOUNDARY`, `CURRENT_TURN_FRAME`, `TOOL_SURFACE`,
  `ACTION_OBLIGATION`, `PERMISSION`, `CHECKPOINT`, `VERIFICATION`,
  `OUTCOME_TRUTH`, `TRACE_REDACTION`, `REPAIR_CONTROL`, `MODEL_COMPETENCE`,
  and `UNSUPPORTED_CAPABILITY`.
- The doc defines prompt families with positive variants, negative controls,
  expected contracts, expected tools, trace signals, blockers, and follow-ups.
- The doc defines scoring: `PASS`, `PASS_WITH_FOLLOWUP`, `FAIL`, `BLOCKER`,
  and `UNSUPPORTED`.
- The doc defines candidate blockers, including secret leaks, unapproved
  mutation, protected path mutation, missing checkpoint before approved
  mutation, false completion after failed verification, final capability denial
  for mutation-capable requests, and trace raw secret leakage.
- The doc explains Terminal-Bench 2 as external pressure, not the Talos release
  gate yet, and defines task labels: `SUPPORTED_NOW`, `PARTIALLY_SUPPORTED`,
  `UNSUPPORTED_TOOL_SURFACE`, and `RESEARCH_SIGNAL`.

## Tests / Evidence

Completed:

- `./gradlew.bat test --no-daemon` - PASS

## Work-Test Cycle Notes

Use the inner dev loop. This design-only ticket does not declare a versioned
candidate and does not update `CHANGELOG.md`.

## Implementation Summary

- Created `docs/evaluation/01-talosbench-live-prompt-matrix.md`.
- Defined TalosBench as a live/manual evaluation framework for installed Talos
  and real local models.
- Added scope, failure taxonomy, prompt families, scoring, trace requirements,
  release blockers, Terminal-Bench relation, and failure-to-ticket workflow.
- Kept this ticket docs-only with no runtime behavior changes.

## Known Risks

- The framework could become too broad to run manually. Keep T49 focused on
  taxonomy and prompt families; T50/T51 can decide runner automation details.
- Terminal-Bench should not become a release gate before Talos has a supported
  command/test-runner capability.

## Known Follow-Ups

- T50 should create a repeatable live prompt runner or semi-manual harness.
- T51 should add trace assertion support for TalosBench summaries.
- Terminal-Bench compatibility should remain a separate evaluation ticket, not
  a 0.9.8 release gate.

## Commit

Commit hash: recorded in final handoff.
