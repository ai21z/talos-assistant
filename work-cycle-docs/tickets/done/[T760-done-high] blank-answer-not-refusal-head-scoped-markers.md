# T760 - Blank Answer Is Not A Refusal; Refusal Markers Scoped To The Answer Head

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (outcome truthfulness in the protected-read postcondition)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

`ProtectedReadAnswerGuard.isGenericProtectedReadRefusal` treated a blank
answer as a refusal, and matched refusal markers ANYWHERE in the answer
(2026-06-10 evaluation, roadmap item W2.5). Two failure modes:

1. Truthfulness: a blank model answer after a granted protected-read
   approval was repaired with the trace reason "generic model refusal
   replaced..." - untruthful; the model refused nothing.
2. Whole-answer marker matching destroyed long grounded answers whose TAIL
   carried a legitimate caveat ("the raw value cannot be shared") but did
   not literally contain the evidence-summary substring.

## Design

- Predicate split: `isBlankAnswer` (blank after a successful approved
  protected read is STILL repaired with the evidence answer - shipping an
  empty final answer after a granted approval is worse - but the trace
  reason now says "blank model answer replaced with current approved read
  evidence") and `isProtectedReadRefusal` (markers retained verbatim,
  matched only within the first 240 chars of the stripped answer:
  REFUSAL_HEAD_CHARS, matching the repo's singleLine convention).
- Head scoping is safe for runtime-injected markers ("approval blocked",
  "protected content was redacted"): they always sit at offset 0 of
  runtime-replaced answers. A refusal buried past the head passes through -
  an answer-quality trade only; disclosure control lives in the
  suppression/handoff/redaction layers, not in this guard.
- The evidence escape hatch (answers containing the read-evidence
  substring are never repaired) is unchanged.

## Executor-level note (discovered while writing the e2e scenario)

At the executor level a blank loop answer is replaced by the loop-summary
fallback BEFORE the postcondition guard runs, so the guard's blank branch
engages only on paths that hand it a truly blank answer. The blank branch
is therefore pinned at guard-unit level; the e2e scenario pins the
answer-head refusal repair end-to-end instead. Reordering the executor's
fallback-vs-postcondition pipeline is out of scope (synthesis-retry
policy, Wave-5 answer-shaping territory).

## Architecture Metadata

Capability: protected-read answer postcondition (runtime.outcome)
Operation(s): read (answer shaping after approved protected reads)
Owning package/class: `dev.talos.runtime.outcome.ProtectedReadAnswerGuard`
New or changed tools: none
Risk, approval, and protected paths: no approval/permission change;
repair-direction unchanged (still replaces with grounded evidence)
Outcome and trace: PROTECTED_READ_POSTCONDITION_CHECKED reason becomes
truthful for blanks; refusal-repair reason text unchanged
Refactor scope: the single guard method split; no pipeline reordering

## Tests / Evidence

- `ProtectedReadAnswerGuardTest`: blank answer repaired with the truthful
  blank-specific trace reason; tail caveat past the 240-char head does NOT
  trigger repair (the key new behavior - this answer was destroyed before);
  head-positioned refusal still repaired; tokenizer.java read does not
  engage the guard at all (T759 interplay); all pre-existing repair/
  pass-through tests green unchanged (their markers are answer-initial).
- E2E scenario `89-approved-protected-read-refusal-repair.json` (executor
  runner): approved protected read + scripted refusal → answer repaired,
  refusal text gone, trace records the postcondition repair.

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green.
