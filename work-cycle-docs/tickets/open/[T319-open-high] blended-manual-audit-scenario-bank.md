# T319 - Blended Manual Audit Scenario Bank

Status: first scenario bank added; open for automation and live-model expansion
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
