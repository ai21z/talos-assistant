# T703 - Static-Web Repair Frame Read-Before-Rewrite Alignment

Status: done
Priority: high
Created: 2026-06-06

## Problem

The Qwen `test02-12` fresh repair turn generated a static-web repair frame that instructed full-file replacement through `talos.write_file`, but it did not instruct the model to read existing files first. The existing runtime guard then blocked writes to `style.css` and `script.js` because those files had not been read in the same turn.

This is a prompt/runtime contract mismatch:

- Repair policy narrows the tool surface toward full-file replacement.
- Rewrite grounding policy correctly requires same-turn reads for existing small web files.
- The repair frame does not tell the model that read-before-write is required.

## Code Evidence

- Static repair instructions say to use `talos.write_file` for complete corrected file content: `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`.
- Existing static-web rewrite grounding blocks full-file writes to existing `index.html`, CSS, or JS targets when no same-turn read exists: `src/main/java/dev/talos/runtime/toolcall/StaticWebRewriteGroundingGuard.java`.
- The audit showed the repair frame asked for `script.js, style.css`, then both writes were blocked with the grounding error.

## Acceptance Criteria

- Static-web full-file repair frames must instruct the model to call `talos.read_file` for each existing full-file replacement target before writing it.
- If `read_file` reports `NOT_FOUND` for a required missing target, the repair frame may instruct creating that file with complete content.
- The instruction must preserve narrowed repair targets and forbidden artifacts.
- The rewrite grounding guard remains intact.

## Test Plan

- Add a focused `RepairPolicyTest` asserting static-web repair instructions include the read-before-rewrite rule.
- Add or update an execution-level test where a compliant read-then-write repair path is allowed, while ungrounded writes remain blocked by the existing guard.

## Notes

This ticket should not weaken the guard. The point is to align the repair prompt with the runtime safety policy that already exists.

## Completion Evidence

- Added `RepairPolicyTest` coverage requiring static-web repair instructions to include read-before-rewrite guidance.
- Updated `RepairPolicy.renderStaticVerificationInstruction(...)` to tell the model to call `talos.read_file` before rewriting existing full-file repair targets and to create missing required targets only after `NOT_FOUND`.
- Re-ran `StaticWebRewriteGroundingGuardTest` to confirm the existing guard behavior remains intact.
- Verified with focused and affected-area Gradle test runs on 2026-06-06.
