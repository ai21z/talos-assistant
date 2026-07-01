# [T52-done-high] Classify Terminal-Bench 2 for Talos evaluation

Status: done
Priority: high

## Context

T49 designed TalosBench as Talos's live prompt evaluation matrix. T50 added a
manual/live runner, and T51 added `/last trace` assertions. Terminal-Bench 2 is
useful external pressure, but it is a terminal/container benchmark while Talos
currently exposes controlled workspace file tools, permissions, trace,
checkpointing, and verification rather than a general shell.

## Goal

Create a compatibility review and task classifier for using Terminal-Bench 2 as
external evaluation signal without treating it as a direct Talos release gate
before Talos has a controlled terminal/test-runner capability.

## Non-Goals

- No shell execution implementation.
- No Terminal-Bench adapter or deep integration.
- No candidate declaration.
- No version bump.
- No `CHANGELOG.md` update.
- No broad benchmark run.
- No new runtime behavior.

## Implementation Notes

Create:

- `docs/evaluation/02-terminal-bench-2-compatibility.md`

The document should cover:

- what Terminal-Bench 2 measures
- why it is useful
- why it is not a direct Talos release gate yet
- task classification labels:
  - `SUPPORTED_NOW`
  - `PARTIALLY_SUPPORTED`
  - `UNSUPPORTED_TOOL_SURFACE`
  - `RESEARCH_SIGNAL`
- how to run it if installed
- how to record results
- how to convert failures into Talos tickets
- requirements before making it a hard gate:
  - controlled test runner
  - shell policy
  - command permissions
  - stdout/stderr trace redaction
  - checkpoint interaction
  - sandboxing

## Acceptance Criteria

- Compatibility doc exists at
  `docs/evaluation/02-terminal-bench-2-compatibility.md`.
- The doc cites current Terminal-Bench/Harbor materials.
- The doc explains Terminal-Bench task structure and Docker/terminal
  requirements.
- The doc defines the four classification labels and how to apply them.
- The doc explains that Terminal-Bench 2 is external pressure, not a current
  Talos release gate.
- The doc includes a result-recording format.
- The doc explains how findings become Talos architecture tickets.
- The doc lists the required foundations before Terminal-Bench can become a
  hard gate.
- No runtime source changes.
- `./gradlew.bat test --no-daemon` passes.

## Tests / Evidence

Completed:

- `./gradlew.bat test --no-daemon` - PASS

## Work-Test Cycle Notes

Use the inner dev loop. This ticket does not declare a versioned candidate and
does not update `CHANGELOG.md`.

## Known Risks

- Terminal-Bench task names alone are not sufficient to classify all tasks.
  Later work must inspect actual task directories before scoring Talos.
- Treating Terminal-Bench as a hard gate before Talos has a controlled command
  runner would produce misleading failures for unsupported capabilities.

## Implementation Summary

- Added `docs/evaluation/02-terminal-bench-2-compatibility.md`.
- Documented Terminal-Bench 2 as external benchmark pressure, not a current
  Talos release gate.
- Defined the `SUPPORTED_NOW`, `PARTIALLY_SUPPORTED`,
  `UNSUPPORTED_TOOL_SURFACE`, and `RESEARCH_SIGNAL` classification labels.
- Added a classification checklist for task triage.
- Documented result-recording fields for future Terminal-Bench explorations.
- Documented how Terminal-Bench findings should become architecture-level Talos
  tickets.
- Listed required foundations before Terminal-Bench can become a hard gate:
  controlled test runner, shell policy, command permissions, stdout/stderr trace
  redaction, checkpoint interaction, and sandboxing.

## Known Follow-Ups

- Inspect actual Terminal-Bench task directories before scoring Talos against a
  subset.
- Use the future evaluation failure-intake workflow to turn benchmark findings
  into architecture-level tickets.
- Do not start Terminal-Bench adapter work until controlled command/test-runner
  policy and sandboxing are designed.
