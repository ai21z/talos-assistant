# T166 - Stale Static Repair Obligations Must Not Hijack Fresh Explicit Turns

Status: open

Severity: high

Source audit:
- `local/manual-testing/llama-cpp-t61g-big-audit-20260506-172941/FINDINGS-LLAMA-CPP-T61G-BIG-AUDIT.md`

## Problem

A pending static repair obligation from one failed task can survive into a fresh
unrelated explicit mutation and control the outcome.

In the T61-G audit, GPT-OSS failed a BMI repair for `scripts.js`. The next user
turn was a fresh exact write:

```text
Overwrite index.html with exactly AFTER. Use talos.write_file.
```

Talos built the correct current-turn exact-write frame for `index.html` and the
model wrote `index.html` exactly, but the final outcome was blocked because the
old `scripts.js` repair obligation was still pending.

## Evidence

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9845-9959`
  - second BMI create fails static verification for `scripts.js`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:10786-10884`
  - repair turn fails with invalid mutation arguments
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:11800-11848`
  - fresh exact `index.html` write is executed, then blocked by stale
    `scripts.js` static repair obligation

## Scope

In scope:
- Scope pending static repair obligations to the task/target set that produced
  them.
- Allow a fresh explicit mutation with disjoint expected targets to supersede
  stale repair state.
- Preserve repair enforcement when the user is actually continuing the failed
  artifact repair.
- Add trace/debug evidence when stale repair state is cleared or superseded.

Out of scope:
- Do not remove static repair enforcement.
- Do not weaken exact-write verification.
- Do not add provider-specific behavior.

## Acceptance

- A failed static repair for `scripts.js` does not block a later exact write to
  `index.html` when the user asks for that fresh exact write.
- A genuine repair follow-up still enforces the pending `scripts.js` repair.
- Tests cover disjoint-target supersession and same-target repair continuation.
- The exact-write final output is success/failure-dominant based on the current
  turn, not an unrelated previous repair.
- `.\gradlew.bat --no-daemon check installDist` passes.
