# T117 - Static Repair Full-Rewrite Targets Must Exclude Wrong Similar Targets

Status: open
Severity: high
Area: static verification / repair framing / expected targets

## Problem

The T116 focused Qwen/GPT-OSS audit shows a repair-plan ambiguity after a wrong similar target mutation.

Talos correctly detects that `script.js` does not satisfy required `scripts.js`, but the static repair context then includes both `script.js` and `scripts.js` in `Full-file replacement targets`. That can reinforce the wrong target instead of making the missing expected target dominant.

Audit evidence:

- `local/manual-testing/t116-llama-cpp-runtime-control-audit-20260503-233238/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1097-1158`
- `local/manual-testing/t116-llama-cpp-runtime-control-audit-20260503-233238/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:2037-2049`
- `local/manual-testing/t116-llama-cpp-runtime-control-audit-20260503-233238/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3037-3048`

Concrete bad frame:

```text
Missing expected targets: scripts.js
Similar changed targets that do not satisfy missing expected targets:
- script.js does not satisfy scripts.js; write or update scripts.js explicitly.

Repair plan:
Full-file replacement targets: script.js, scripts.js
- scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
- script.js: You must use talos.write_file with complete corrected file content for script.js.
```

`script.js` should be evidence of the mistake, not a required full-rewrite target, unless it was explicitly expected by the current task.

## Scope

- Update static repair full-rewrite target selection so wrong similar targets are not promoted into repair targets.
- Keep similar wrong targets in the diagnostic/evidence section.
- Preserve expected target dominance: missing expected targets must be named and prioritized.
- Preserve coherent web repair for originally expected HTML/CSS/JS targets.
- Do not suppress verifier reporting of similar wrong targets.

## Acceptance

- Tests cover expected target `scripts.js` with wrong similar changed target `script.js`.
- Repair context says `script.js` does not satisfy `scripts.js`.
- `Full-file replacement targets` includes `scripts.js` and other required expected targets needed for coherent repair.
- `Full-file replacement targets` does not include `script.js` unless `script.js` was also an expected target.
- Runtime-owned changed-files summary remains accurate and failure-dominant.
- No regression to T95/T99 expected-target repair tests.

## Non-Goals

- No deterministic static app generator.
- No model-specific special casing.
- No broad prompt rewrite.
- No full T61-style audit in this ticket.
