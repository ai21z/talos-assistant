# [T837-open-high] Run Command Output Handoff Boundary

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 high trust gap where `run_command` stdout/stderr reaches model
context after only best-effort text redaction rather than an explicit privacy
handoff decision.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/research/opus-wave6-deep-research-review-20260618.md`

## Scope

- Tag command output with command-output content metadata.
- Tag command output, route it through the privacy handoff, and keep the
  model-context handoff boundary aligned with other sensitive tool results.
- Withhold or summarize command output that contains secret-shaped or
  high-entropy content before it reaches model context.
- Preserve truthful final-answer behavior for failed, denied, or timed-out
  command runs.

## Acceptance Criteria

- Tests prove bare tokens, JWTs, PEM blocks, connection strings, and
  high-entropy command output do not pass raw into model context.
- Normal non-sensitive command output remains usable for verification answers.
- Session and trace persistence do not store raw withheld command secrets.
- Docs do not claim command output is fully safe; they describe the bounded
  behavior that actually ships.

## Non-Goals

- Do not add arbitrary shell execution.
- Do not weaken command profile bounds, timeout handling, or argv validation.

## Implementation State

Implemented for review in T837. The change:

- tags executed command results with `COMMAND_OUTPUT` / `COMMAND` metadata;
- keeps non-sensitive command output visible to the model for verification;
- withholds command stdout/stderr from model context when command capture
  required protected-content redaction;
- preserves failure-dominant behavior for failed and timed-out commands;
- updates public/docs wording to the bounded post-T837 claim;
- leaves this ticket open for review and closeout.

Report:

- `work-cycle-docs/reports/t837-run-command-output-handoff-boundary.md`

Implementation commit SHA is intentionally left for closeout after review.
