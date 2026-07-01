# Failure Intake And Ticketing

Status: evaluation workflow.

Date: 2026-04-29

This document defines how Talos converts manual prompt failures, TalosBench
results, and external benchmark findings into architecture-level tickets.

The purpose is to prevent one-off prompt patches. A failed prompt is evidence,
not the ticket by itself. The ticket should name the runtime boundary,
verification gap, policy ownership problem, or supported capability failure
that the prompt exposed.

## 1. Record Failure

Every failure report must capture enough evidence to reproduce, classify, and
turn the finding into a deterministic regression.

Required fields:

- prompt sequence
- workspace fixture or setup notes
- model/backend
- Talos version and commit when known
- transcript path
- `/last trace` or local trace summary
- expected behavior
- observed behavior
- files changed, if any
- approval choices, if any
- checkpoint id, if any
- verification status, if any
- whether raw sensitive values appeared in output or trace

Raw transcripts should stay under ignored local evidence paths such as:

```text
local/manual-testing/
```

Tracked docs and tickets should include concise summaries and redacted excerpts
only.

## 2. Classify Failure

Use the TalosBench taxonomy. A finding may have secondary contributing buckets,
but the ticket should identify one primary architectural bucket.

| Bucket | Use when |
| --- | --- |
| `INTENT_BOUNDARY` | The `TaskContract` or mutation/read-only classification does not match the request. |
| `CURRENT_TURN_FRAME` | The model is not clearly told current runtime capability, visible tools, phase, or task obligation. |
| `TOOL_SURFACE` | The visible tool set is too broad, too narrow, or wrong for the task. |
| `ACTION_OBLIGATION` | The model response fails the required action type, such as returning snippets when mutating tools are required. |
| `PERMISSION` | Protected resources, allow/ask/deny rules, or approval labels are wrong. |
| `CHECKPOINT` | Approved mutation lacks a checkpoint, restore fails, or checkpoint state is confusing. |
| `VERIFICATION` | Talos verifies the wrong thing or misses a task-specific success condition. |
| `OUTCOME_TRUTH` | The final answer contradicts structured tool, permission, verification, or history evidence. |
| `TRACE_REDACTION` | Trace or `/last` leaks sensitive values or omits required policy evidence. |
| `REPAIR_CONTROL` | Repair retries blindly, ignores verifier findings, or fails to stop cleanly. |
| `MODEL_COMPETENCE` | Runtime policy is correct, but the model produces weak content while Talos remains safe and truthful. |
| `UNSUPPORTED_CAPABILITY` | The user or benchmark asks for capabilities outside the current Talos tool surface. |

Do not create one ticket per wording variant. Group related failures into the
same bucket when they share the same runtime cause.

## 3. Decide Blocker Level

Use one of these levels:

| Level | Meaning | Examples |
| --- | --- | --- |
| release blocker | Candidate should not proceed until fixed. | Secret leak, unapproved mutation, protected path mutation, missing checkpoint before approved mutation, false completion after failed verification, mutation-capable request final-answering with capability denial. |
| candidate follow-up | Candidate can proceed if Talos stays safe, bounded, and truthful. | Awkward wording, over-verbose trace, live repair does not complete but reports precise failure. |
| future milestone | Useful capability or architecture work outside the current candidate scope. | Controlled command runner, browser automation design, better document handling. |
| unsupported | The finding depends on a tool surface Talos intentionally does not expose. | Terminal-Bench task requiring shell, Docker, server startup, package install, or browser execution. |

When in doubt, treat safety, privacy, permission, checkpoint, and outcome truth
failures as blockers until reviewed.

## 4. Require Architectural Hypothesis

Every ticket must state the likely architectural cause. The hypothesis may be
wrong, but it must be specific enough to guide investigation.

Bad:

```text
Fix the BMI prompt.
```

Good:

```text
Mutation-capable create turns need current-turn tool-use obligation
enforcement, because the runtime resolved FILE_CREATE with write tools visible
but the model returned no-tool capability denial prose.
```

Bad:

```text
Make folder listing safer.
```

Good:

```text
Simple directory-listing prompts need a list-only contract/tool surface so
Talos does not expose content-inspection tools for filename-only requests.
```

The hypothesis should include:

- primary taxonomy bucket
- current expected invariant
- observed invariant violation
- likely code ownership
- why a narrow prompt patch would be insufficient

## 5. Require Regression Path

Every implementation ticket created from evaluation evidence must define at
least one deterministic regression path and one manual/live validation path.

Regression options:

- unit test for policy, resolver, verifier, or outcome rendering
- executor or mode integration test
- JSON e2e scenario
- TalosBench prompt family
- TalosBench trace assertion
- manual installed Talos prompt case

Minimum bar:

- For runtime policy fixes, add a focused unit/integration test.
- For model-output failure modes, add a deterministic scripted e2e scenario.
- For live-model behavior, add or update a TalosBench prompt family.
- For trace-sensitive failures, add a trace assertion.

If a finding cannot be converted into a deterministic regression, the ticket
must explain why and record the manual evidence needed for future review.

## 6. Require Non-Goals

Every ticket created from evaluation evidence must include non-goals that keep
the fix inside the current milestone.

Default non-goals:

- no shell/browser unless the milestone explicitly includes it
- no MCP or multi-agent behavior unless explicitly approved
- no LLM classifier for safety-critical permission, privacy, mutation, or
  verification policy
- no giant untyped phrase dump without an owner policy
- no bypassing approval, permission, checkpoint, trace, or verification
- no committing raw private transcripts

If a finding comes from Terminal-Bench, also include:

- no Terminal-Bench adapter unless the ticket explicitly scopes it
- no treating unsupported shell/test-runner tasks as Talos release blockers

## 7. Ticket Template

Use:

```text
work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md
```

The template requires:

- status and priority
- evidence summary
- taxonomy bucket
- blocker level
- architectural hypothesis
- goal
- non-goals
- implementation notes
- acceptance criteria
- tests/evidence
- manual/TalosBench cases
- work-test cycle notes
- known risks and follow-ups

## Intake Workflow

Use this sequence for manual and benchmark failures:

1. Save raw evidence locally.
2. Write a short redacted finding summary.
3. Classify the failure with the TalosBench taxonomy.
4. Assign blocker level.
5. Write the architectural hypothesis.
6. Decide whether the finding is a duplicate of an existing open ticket.
7. If not a duplicate, create a ticket from the evaluation-finding template.
8. Add deterministic regression requirements.
9. Add a TalosBench/manual prompt rerun case.
10. Implement only after the ticket is reviewed or clearly prioritized.

This workflow intentionally separates evidence collection from implementation.
Do not let a surprising prompt immediately become a source edit.

## Review Checklist

Before accepting a new evaluation-derived ticket, verify:

- The raw transcript path is recorded locally.
- The ticket contains a redacted summary, not raw private content.
- The taxonomy bucket is explicit.
- The blocker level is justified.
- The hypothesis names an architectural boundary.
- The non-goals prevent scope creep.
- The regression path includes deterministic coverage where practical.
- The manual rerun case is concrete.
- The ticket is not a duplicate.

## Examples

### Capability Denial On Mutation Request

Evidence:

```text
User: I want to create a modern BMI calculator website to use. Can you make it?
Trace: FILE_CREATE, mutationAllowed=true, write/edit tools visible.
Assistant: I cannot create or modify files.
```

Classification:

```text
CURRENT_TURN_FRAME + ACTION_OBLIGATION
```

Ticket shape:

```text
Current-turn mutation capability frame and mutating-tool obligation must prevent
false no-filesystem-access final answers.
```

Regression:

```text
Scripted e2e where first model response refuses tools, retry emits write_file,
and final answer excludes false capability denial.
```

### Terminal-Bench Task Requires Shell

Evidence:

```text
Task requires compiling a native extension and running verifier tests.
```

Classification:

```text
UNSUPPORTED_CAPABILITY
```

Ticket shape:

```text
No immediate runtime ticket. Record as future controlled test-runner evidence.
```

Regression:

```text
None until command/test-runner milestone is approved.
```

### Trace Leaks Secret-Like Prompt Value

Evidence:

```text
/last trace shows SECRET=changed from the user prompt.
```

Classification:

```text
TRACE_REDACTION
```

Ticket shape:

```text
Human-readable trace previews must redact secret-like KEY=value values while
preserving path/tool/policy metadata.
```

Regression:

```text
Trace rendering test plus TalosBench transcriptExcludes assertion.
```
