# T122 - Repair Read-Only Loop Budget Before Mutation Retry

Severity: medium

## Problem

The T121 focused Qwen/GPT-OSS managed llama.cpp audit showed GPT-OSS can enter a repair/fix turn, repeatedly inspect the same static web files, hit the tool-loop iteration limit, and only then fall into the T120 `REPAIR_INSPECTION_ONLY` containment path.

This is safe, but inefficient:

- no file is changed,
- no approval is requested,
- final output is failure-dominant,
- trace records `failureKind=REPAIR_INSPECTION_ONLY`,
- but the model can spend many iterations on read-only calls before the deterministic breach.

The problem is not prompt construction. It is repair-loop control: a mutation-required repair turn should allow enough inspection to form a valid write/edit, but it should not spend the full tool-loop budget on repeated reads when no mutating tool is attempted.

## Scope

- Add a bounded read-only repair budget for mutation-required repair/fix turns.
- When a repair/fix turn has used only read-only tools after enough inspection and has not attempted any mutating tool, trigger the existing T120 deterministic repair-inspection-only outcome earlier.
- Preserve normal non-repair read-only inspection behavior.
- Preserve repair happy paths where the model reads first, then calls `talos.write_file` or `talos.edit_file`.
- Preserve T121 wrong-tool classification when the model does attempt `talos.edit_file` for a full-rewrite repair target.

## Acceptance

- A scripted repair/fix turn that repeatedly calls only read-only tools reaches `REPAIR_INSPECTION_ONLY` before the general tool-loop iteration limit.
- The final output remains failure-dominant and contains no model-authored success prose.
- Trace includes a clear action-obligation failure with `failureKind=REPAIR_INSPECTION_ONLY`.
- A repair/fix turn that reads the relevant files and then mutates still succeeds.
- General read-only QA turns are not affected.

## Evidence

- `local/manual-testing/t121-static-repair-wrong-tool-audit-20260504-052149/FINDINGS-T121-STATIC-REPAIR-WRONG-TOOL-AUDIT.md`
- GPT-OSS final review/fix turn used repeated `talos.read_file` calls, hit the iteration limit, and then was blocked as `REPAIR_INSPECTION_ONLY`.

## Non-Goals

- No provider abstraction.
- No prompt wording rewrite.
- No full T61-style audit.
- No weakening of expected-target scope enforcement.
