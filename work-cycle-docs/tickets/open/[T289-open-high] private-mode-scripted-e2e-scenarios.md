# T289 - Private Mode Scripted E2E Scenarios

Status: open - initial scripted e2e coverage added, live audit still required
Severity: high
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Private mode had unit/integration coverage, but broader full-turn scripted evidence was still thin.

## Evidence from current code

`src/e2eTest/java/dev/talos/harness/PrivateModeScriptedE2eTest.java` drives the real tool loop with private-mode config and scripted model follow-ups.

## Evidence from tests/audits

Initial scenarios cover:

- private-mode `.env` read approved as local-display-only does not enter model context
- private-mode grep over `.env` omits raw canary content

## User impact

Users need evidence that private mode changes whole-turn behavior, not only isolated policy methods.

## Product risk

Private-document positioning remains blocked until scripted e2e and live audit evidence both pass.

## Runtime boundary affected

Tool result handoff, model context, protected direct reads, indirect grep results.

## Non-goals

- Do not replace live two-model audit.
- Do not claim private-document readiness from scripted tests alone.

## Required behavior

Expand scripted private-mode e2e coverage for retrieve disabled, prompt-debug save redaction, session/turn log redaction, trace redaction, command-output redaction, sensitive workspace warnings, and unsupported document truthfulness.

## Proposed implementation

Continue adding deterministic private-mode e2e tests under `src/e2eTest/java/dev/talos/harness/`.

## Tests

`./gradlew.bat e2eTest --tests "*PrivateModeScriptedE2e*" --no-daemon`

## Acceptance criteria

- Scripted private-mode e2e tests pass.
- Full two-model live audit still runs before any private-document beta claim.

## Remaining blockers

Live two-model audit is blocked by model setup.

## Open questions

Should the JSON scenario runner grow explicit config overrides so private-mode scenarios can live in resource files?

## Related files

- `src/e2eTest/java/dev/talos/harness/PrivateModeScriptedE2eTest.java`
- `work-cycle-docs/reports/t267-live-two-model-audit.md`
