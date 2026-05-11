# T239 - Source-Derived Artifact Verification And Evidence Accounting

Severity: high

## Problem

The broad user-perspective re-audit shows Qwen can satisfy the tool-level shape of a source-to-target summary request while writing the user's instruction instead of a real source-derived summary.

The same turn also reports `[Evidence incomplete]` even though the trace shows `talos.read_file -> long-notes.txt [ok]` and `talos.write_file -> docs/summary.md [ok]`.

## Evidence

Audit:
`local/manual-testing/user-perspective-broad-reaudit-20260511-143729/FINDINGS-USER-PERSPECTIVE-BROAD-REAUDIT.md`

Transcript:
- Prompt and source-target frame: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2486`
- Bad write preview: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2514`
- Evidence incomplete emitted: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2524`
- Trace lists source read and target write: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:2543` and `2544`
- Later read confirms target contains instruction text: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:3207`

## Scope

- Fix source-evidence accounting so a required source read in the same turn satisfies the source evidence obligation.
- Add deterministic verification for simple source-derived artifact writes, starting with summarize-source-into-file requests.
- Detect obvious non-derived outputs such as:
  - target content repeats the user instruction,
  - target content contains no meaningful source-derived terms,
  - target content ignores simple requested output shape such as "under 8 bullets" when it is easy to check.
- Failure must be failure-dominant or partial, not advisory-only success.
- Do not add a broad semantic grading system.

## Acceptance

- A scripted source summary turn that reads `long-notes.txt`, then writes `docs/summary.md` with only the current instruction, fails verification.
- A scripted source summary turn that reads `long-notes.txt`, then writes concise bullets containing source facts such as `Neon Harbor`, passes.
- The evidence gate no longer emits evidence-incomplete when the required source file was read in the same turn.
- Existing non-source file creation remains readback-only unless another verifier applies.
- Targeted tests and full Gradle tests pass.

