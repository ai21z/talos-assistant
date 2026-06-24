# [T873-open-medium] Broader command-output truthfulness (non-git-status shapes)

Status: open
Priority: medium

## Evidence Summary

- Source: T842 live audit follow-up (extends T866)
- Date: 2026-06-24
- Talos version / commit: 0.10.5 / c4e0374f
- Model/backend: managed llama.cpp; qwen2.5-coder-14b and gpt-oss-20b
- Workspace fixture: not applicable (extension of the T866 guard)
- Raw transcript path: see T842 (`local/manual-testing/capability-live-audit-20260624-173843/`)
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime diff yet; extension scope
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: not run (follow-up scope)

Redacted prompt sequence:

```text
T866 fixed the confirmed git-status fabrication class. Other command/tool output
shapes (test-run results, process lists, ls/cat listings, "the file contains X"
without a successful read) can still be fabricated by a weak model when the
producing tool is off-surface or did not run successfully, with no deterministic
grounding.
```

Expected behavior:

```text
An answer must not present command/tool output of ANY recognized shape that no
successful producing tool call produced this turn. The deterministic outcome
layer detects the missing grounding from the executed-tool ledger and
annotates/withholds -- the same ledger-grounded pattern T866 established for
git-status, extended to additional recognizable output shapes.
```

Observed behavior:

```text
T866's CommandOutputTruthfulnessGuard covers the git-status shape only
(GIT_STATUS_LINE plus the git-status keyword gate). Other fabricated-output shapes
are not yet detected. The git-status repro was the only one observed live in the
T842 audit; the broader shapes are an identified-but-unobserved extension.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`
- `MODEL_COMPETENCE`

Blocker level:

- candidate follow-up

Why this level:

```text
Not a confirmed live failure (only git-status was observed, and it is now fixed by
T866), and not a hard candidate-cut blocker. It matters for the public
truthfulness claim: until broader command/tool-output grounding is deterministic,
the public claim must stay precise that the no-change/no-success correction is
strongest for file-mutation turns and (now) the git-status command shape. Track
and fix before generalizing any "does not fabricate tool output" claim.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add a regex per command shape as a denylist. A surface-phrase denylist is brittle
and drifts toward over-firing; the grounding must stay ledger-first.
```

Architectural hypothesis:

```text
Reuse the T866 ledger-grounded pattern. The primary decision stays "no successful
producing tool in the executed-tool ledger this turn." Extend the shape-recognition
arm (beyond GIT_STATUS_LINE) to additional high-confidence command/tool-output
shapes, or generalize to "the answer presents a fenced/structured result attributed
to a tool/command that did not successfully run this turn." Keep each per-shape
recognizer narrow and ledger-gated to preserve the low false-positive profile T866
achieved (recognizer fires only inside its own shape/keyword context AND with no
successful producer in the ledger).
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/outcome/CommandOutputTruthfulnessGuard.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`

Why a one-off patch is insufficient:

```text
Each new shape added ad hoc risks either over-firing (annotating a legitimately
labeled tool result) or under-covering. The extension must keep the ledger as the
gate and add shapes behind it, each with both-direction tests, or the truthfulness
guarantee stays git-status-only with a widening gap.
```

## Goal

```text
Fabricated command/tool output of recognized non-git-status shapes (at minimum:
test-run results, process lists, ls/cat-style listings, and file-content claims
made without a successful read) is deterministically annotated or withheld when no
successful producing tool is in the turn's ledger, with no over-fire on grounded or
honestly-labeled answers.
```

## Non-Goals

- No LLM classifier for the gate.
- No surface-phrase denylist as the primary mechanism; the executed-tool ledger is
  the gate.
- Do not add off-surface tools to surfaces where they are narrowed off (that is T872).
- Do not re-open the git-status path (covered by T866) except to refactor shared
  shape-recognition.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Implementation Notes

```text
Extend CommandOutputTruthfulnessGuard with additional ledger-gated shape
recognizers (or generalize the producing-tool check). Each added shape needs a
both-direction test: fabricated-without-producer -> withheld; grounded-by-a-real-
tool or honestly-labeled -> untouched, mirroring the T866 boundary tests. A
recognizer must only fire inside its keyword/shape context AND with no successful
producer in the ledger. Reuse the T866 / T834 ledger plumbing; do not fork a
parallel evidence source.
```

## Architecture Metadata

Capability:

- Deterministic read/command answer-output grounding (extension of T866).

Operation(s):

- `verify`

Owning package/class:

- `dev.talos.runtime.outcome.CommandOutputTruthfulnessGuard`
- `dev.talos.cli.modes.ExecutionOutcome`

New or changed tools:

- No new tool names.

Risk, approval, and protected paths:

- Risk level: outcome-truth (answer grounding).
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: the withhold/annotate verdict and its ledger basis must be
  captured in trace, as with T866.
- Verification profile: deterministic outcome-layer grounding against the
  executed-tool ledger.
- Repair profile: unchanged.

Outcome and trace:

- Outcome/truth warnings: extend the `UNSUPPORTED_COMMAND_OUTPUT_CLAIM` path (or a
  sibling) to the new shapes.
- Trace/debug fields: record which shape recognizer fired and the absent producer.

Refactor scope:

- Allowed: factor shared shape-recognition + ledger-gate helpers out of the T866
  guard.
- Forbidden: broad outcome-layer or tool-loop rewrite; LLM-based detection.

## Acceptance Criteria

- At least the named non-git-status shapes (test-run results, process lists,
  ls/cat listings, file-content-without-a-successful-read) are covered, each with a
  both-direction deterministic test.
- The git-status path (T866) and the mutation anti-overclaim path (T834) remain
  green and unchanged in behavior.
- The grounding verdict is deterministic and ledger-derived, with no LLM classifier.
- No over-fire: grounded or honestly-labeled tool output passes unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: per added shape, a fabricated-without-producer case -> withheld and a
  grounded/labeled case -> untouched (mirror `CommandOutputTruthfulnessGuardTest`).
- Integration/executor test: `ExecutionOutcomeTest` coverage of the wiring for the
  new shapes.
- Trace assertion: the warning + ledger basis recorded for a withheld non-git-status
  shape.

Manual/TalosBench rerun:

- Prompt family: command-output requests where the producing tool is off-surface
  (test run, process list, ls/cat, "what does file X contain" without a read).
- Workspace fixture: the T842 fixture style.
- Expected outcome: fabricated output withheld/annotated; honest/grounded answers
  unchanged.

Commands:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.outcome.*" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop; this is not a candidate closeout.
- Add a one-line `## [Unreleased]` `CHANGELOG.md` entry when it lands.
- Cross-ref T866 (git-status increment) and T834 (mutation-scoped anti-overclaim,
  the structural precedent).

## Known Risks

- Over-fire on legitimately labeled tool output if a shape recognizer is too broad;
  keep each recognizer ledger-gated and context-scoped.
- Model-competence ceiling: deterministic annotation bounds the harm but cannot make
  a weak model produce the correct result.

## Known Follow-Ups

- Revisit the public truthfulness claim wording once broad read/command grounding is
  deterministic (currently precise to file-mutation turns + the git-status shape).
- Consider an explicit "tool unavailable" affordance the model can cite (shared with
  the T866 follow-up), reducing the incentive to fabricate.
