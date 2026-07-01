# T319 - Blended Manual Audit Scenario Bank

Status: done
Severity: high
Release gate: yes for broad manual beta confidence
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The current manual prompt bank is strong, but the user transcript showed failures that emerge from blended workflows rather than isolated probes:

- unsupported document creation refusal
- supported text artifact creation
- deictic follow-up creation
- correction after incomplete artifact quality
- repeated read-only no-progress loop
- `/last trace` audit evidence

The audit strategy needs blended scenario sets that combine capabilities and failure modes across turns.

## Required Behavior

Every manual milestone audit should include multi-turn flows that mix:

- identity and workspace explanation
- protected read denial and approved protected read behavior
- unsupported binary output honesty
- document extraction/read versus document generation claims
- static website creation from a source text file
- styling/artifact completeness repair
- approval denial and retry
- failure-policy truthfulness
- trace and prompt-debug evidence capture

## Deliverables

- Add a transcript-grading worksheet for blended scenarios.
- Add deterministic E2E equivalents for confirmed runtime-owned failures.
- Keep live-model transcript runs separate from deterministic regression tests.

## Evidence

First scenario bank added:

- `work-cycle-docs/blended-manual-audit-scenario-bank.md`

The first bank covers:

- source text to styled static site,
- protected read denial and artifact hygiene,
- private document extraction boundary,
- static web selector repair,
- approval denial and retry discipline,
- workspace organization tools.

## Non-Goals

- Do not replace unit tests with manual audits.
- Do not claim a full audit if registered native tools are skipped.

## 2026-06-07 focused audit note

The T719/T720 focused P21 audit surfaced a useful blended-scenario variant:

- In a fresh Qwen session with no prior creation history, the deictic prompt
  `Review the BMI calculator you just created...` did not exercise the intended
  no-change diagnostic branch. Qwen attempted an invalid
  `bmi_calculator.html` edit and the runtime blocked it before approval.
- An explicit-read Qwen variant did exercise the intended no-change branch and
  produced the corrected diagnostic-inspection wording with
  `SATISFIED_BY_INSPECTION` and `Verification: NOT_RUN`.

This is not a T720 regression. It belongs in blended/manual scenario design as
evidence that deictic prompts without sufficient session/workspace context can
probe model/tool-loop convergence separately from deterministic policy wording.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as superseded by the blended-manual-audit scenario bank (now expanded with Sequence G); future scenario additions are new tickets.

Closed by independent review as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
