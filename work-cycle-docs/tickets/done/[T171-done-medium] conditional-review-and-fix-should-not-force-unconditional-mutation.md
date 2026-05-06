# T171 - Conditional Review-And-Fix Should Not Force Unconditional Mutation

Status: done

Severity: medium

Source audit:
- `local/manual-testing/llama-cpp-t61h-full-audit-20260506-191922/FINDINGS-LLAMA-CPP-T61H-FULL-AUDIT.md`

## Problem

Prompts such as:

```text
Review the BMI calculator you just created and fix any obvious issue that would stop it from working in a browser.
```

are conditional. Talos should inspect first, then require mutation only if an
actual blocking issue is identified or a repair is proposed.

Today this prompt is classified as `FILE_EDIT` with
`MUTATING_TOOL_REQUIRED`. That is too blunt. If the model inspects the current
files and finds no obvious blocking issue, the turn cannot complete cleanly
without making an unnecessary edit.

The action-obligation gate is correct for explicit mutation requests. The gap is
the task contract for conditional review/fix requests.

## Evidence

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:10765`
  - user asks to review and fix any obvious browser-blocking issue
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:10767-10782`
  - Talos classifies the prompt as `FILE_EDIT`,
    `MUTATING_TOOL_REQUIRED`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:10786-10792`
  - GPT-OSS uses read-only inspection tools and Talos blocks the turn because no
    file changed
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:10807-10816`
  - trace confirms list/read tools only and deterministic action-obligation
    failure

Qwen took a different path by editing `scripts.js`:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:10686-10695`
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:10701-10715`

## Scope

In scope:
- Add or refine a task contract for conditional repair/fix prompts.
- Require read-only inspection evidence first.
- Allow an evidence-backed no-change result when no blocking issue is found.
- If the assistant proposes or identifies a concrete repair, require a mutating
  tool call before completion.
- Keep explicit edit/create/write/fix-this-file prompts under the existing
  `MUTATING_TOOL_REQUIRED` behavior.

Out of scope:
- No broad planner.
- No semantic browser execution.
- No relaxation for explicit mutation requests.

## Acceptance

- A conditional review/fix prompt can complete as no-change only after relevant
  read-only inspection.
- A conditional review/fix prompt that identifies a concrete repair but emits no
  mutation is blocked with an action-obligation failure.
- Explicit mutation prompts still require mutating tools.
- Tests cover the GPT-OSS T61-H read-only-inspection shape and the Qwen
  edit-after-inspection shape.
- `.\gradlew.bat --no-daemon check installDist` passes.
