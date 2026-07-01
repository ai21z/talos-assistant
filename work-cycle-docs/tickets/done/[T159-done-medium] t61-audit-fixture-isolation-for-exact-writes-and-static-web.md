# T159 - T61 Audit Fixture Isolation For Exact Writes And Static Web

Status: done

Severity: medium

## Problem

The current T61 prompt sequence mixes exact-literal overwrite probes and static-web repair probes in the same workspace without restoring the static-web fixture.

That makes one result ambiguous:

1. the audit overwrites `index.html` with exactly `AFTER`;
2. the next step asks only to fix `script.js`;
3. static verification fails because `index.html` is no longer a web page.

The failure is honest for the mutated workspace, but it is not clean evidence about whether the model can repair the `script.js` selector bug.

## Evidence

T61-F managed llama.cpp audit:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/FINDINGS-LLAMA-CPP-T61F-FULL-AUDIT.md`
- Runner prompt step 17:
  - `Overwrite index.html with exactly AFTER. Use talos.write_file.`
- Runner prompt step 18:
  - `Make script.js fix the selector bug by changing .missing-button to .cta-button.`
- Qwen and GPT-OSS both edit `script.js`, then static verification fails because the overwritten `index.html` does not link `styles.css` or `script.js`.

## Scope

- Update the large T61-style audit prompt sequence or runner fixture setup so exact-literal overwrite probes cannot contaminate later static-web probes.
- Acceptable approaches:
  - use separate sub-workspaces for exact-literal and static-web groups;
  - restore the static-web fixture before selector-repair probes;
  - move exact `index.html` overwrite to the end of the audit after static-web probes.
- Keep the exact-literal probe because it is still valuable.
- Keep the static-web selector-repair probe because it is still valuable.

## Acceptance

- T61-style runner creates clean evidence for exact literal writes and static-web selector repair.
- Static-web selector repair starts from a real HTML/CSS/JS fixture, not from `index.html` containing `AFTER`.
- Prompt guide documents the fixture reset/isolation rule.
- The audit findings template distinguishes audit-design failures from product-runtime failures.
- No change to Talos runtime behavior unless a separate product ticket requires it.

## Non-Goals

- Do not weaken `StaticTaskVerifier`.
- Do not hide real whole-app incoherence when the user truly asks to repair a static page.
- Do not start the next full release-confidence audit until this sequence is fixed or the limitation is explicitly called out.
