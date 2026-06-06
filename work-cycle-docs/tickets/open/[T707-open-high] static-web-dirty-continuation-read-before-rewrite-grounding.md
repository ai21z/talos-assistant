# T707 - Static-Web Dirty Continuation Read-Before-Rewrite Grounding

Status: open
Priority: high
Created: 2026-06-06

## Problem

The post-T705 Qwen focused audit showed that dirty static-web continuation no longer gets swallowed by the verification-status answer path, but it still fails before repair because the model attempts full-file writes to existing static-web targets without same-turn reads.

The prompt was action-oriented:

```text
Make this Retrocats website even more polished and complete. Use Tailwind correctly, preserve facts, and repair anything unverified.
```

The trace resolved `FILE_EDIT`, `STATIC_WEB`, mutation allowed, verification required, then failed with `STATIC_WEB_REWRITE_GROUNDING`.

## Code Evidence

- `StaticWebRewriteGroundingGuard` correctly blocks existing full-file rewrites without same-turn read evidence.
- T703 added read-before-rewrite instruction to static verification repair frames, but this dirty continuation path can enter a mutation-capable static-web rewrite without receiving the same concrete read-first guidance.
- The audit transcript is in `local/TalosTestOUTPUT/test02-13-post-t705-qwen-focused-20260606-173052/artifacts/qwen/SESSION-DIRTY-OUTPUT.txt`.

## Acceptance Criteria

- Dirty/continuation static-web rewrite prompts that target existing `index.html`, CSS, or JS must either:
  - expose/steer a deterministic read phase before full-file `write_file`, or
  - include explicit read-before-write obligations in the current-turn frame/prompt.
- The grounding guard remains intact.
- Status-only questions remain read-only.
- A regression test proves the dirty continuation prompt reaches a read-grounded tool path or produces a targeted read-first retry rather than repeated blocked writes.

## Test Plan

- Add an executor/tool-loop test using an existing static-web workspace and the dirty continuation prompt.
- Assert the current-turn prompt or retry frame includes read-before-write obligations for existing full-file targets.
- Assert ungrounded `write_file` remains blocked by `StaticWebRewriteGroundingGuard`.

## Notes

This is not T705 and not visual verification. It is the next runtime repair-convergence issue after T702/T703/T705.

