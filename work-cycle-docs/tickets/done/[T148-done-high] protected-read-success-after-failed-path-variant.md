# T148 - Protected Read Success After Failed Path Variant

Severity: high
Status: done

## Problem

The product workflow audit showed that a successful approved protected read can
still be rendered as incomplete if the model first tried a bad path variant.

GPT-OSS first called `talos.read_file` with ` .env`, which failed as not found.
It then called `talos.read_file` with `.env`, approval was granted, and the read
succeeded. The final outcome still became `BLOCKED_BY_POLICY` with a protected
read incomplete message.

## Scope

- Adjust protected-read postcondition/evidence aggregation so a later successful
  approved read for the required protected target satisfies the turn.
- Preserve failure when all protected read attempts fail or approval is denied.
- Preserve redaction and local-only trace behavior.

## Acceptance

- Failed protected path variant followed by successful approved `.env` read can
  answer the requested value.
- Denied protected read remains blocked.
- Failed-only protected read remains blocked.
- Tests cover GPT-OSS-shaped leading-space path then correct path.

## Evidence

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- GPT-OSS trace: `trc-ef9c50a7-7d20-4b6a-8e41-e3dae717510c`

## Non-Goals

- No weakening protected path approval.
- No prompt-debug protected-content opt-in changes.
- No model-specific workaround.

## Result

- Aggregated protected-read attempts across the turn so a failed path variant
  does not hide a later successful approved read of the required protected
  target.
- Preserved denied-read blocking and failed-only protected read failure.
- Added verifier-level and final outcome regressions for the GPT-OSS-shaped
  leading-space `.env` attempt followed by a correct `.env` read.
