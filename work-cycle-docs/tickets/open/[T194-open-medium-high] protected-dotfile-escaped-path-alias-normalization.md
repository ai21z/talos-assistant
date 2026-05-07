# T194 - Protected Dotfile Escaped Path Alias Normalization

Status: open
Severity: medium/high

## Evidence

Source audit:

- `local/manual-testing/llama-cpp-t61o-full-e2e-audit-20260507-162435/FINDINGS-LLAMA-CPP-T61O-FULL-E2E-AUDIT.md`

Concrete evidence:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3963-3980`
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:3996-4020`

## Problem

GPT-OSS emitted `\.env` for a user-approved `.env` read. Talos classified it as `WORKSPACE_ESCAPE`, did not request approval, and safely blocked.

Containment was correct, but the approved protected-read workflow failed because of a narrow model path spelling issue.

## Scope

In scope:

- Add narrow path alias handling for escaped workspace-relative dotfiles when the expected target is the matching dotfile.
- Preserve protected read approval. Normalization must not bypass approval.
- Keep absolute Windows paths and real workspace escapes blocked.
- Record the normalized alias in trace/debug when applied.

Out of scope:

- Broad path autocorrection.
- Reading protected files without approval.
- Normalizing arbitrary leading backslash paths.

## Acceptance

- `\.env` can resolve to `.env` only when `.env` is the current expected protected target.
- Approval is still required and visible before content is read.
- `\Windows\system32\...`, `\..\secret`, and unrelated escaped paths remain blocked.
- Tests cover both denied and approved protected-read flows.

