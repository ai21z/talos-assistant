# T145 - Directory Create Expected-Target Scope

Severity: high
Status: open

## Problem

The product workflow audit showed that explicit directory creation can be
blocked before approval because the expected target set contains only the file
to be created.

Prompt:

`Create docs/notes with talos.mkdir, then create docs/notes/implementation-plan.md ...`

Talos rejected `talos.mkdir` for `docs/notes` as outside the expected target
set, even though the directory was explicitly requested and is the parent of the
expected file target.

## Scope

- Allow `talos.mkdir` for explicitly requested directory targets.
- Allow `talos.mkdir` for parent directories of expected file-create targets
  when the user explicitly asks for the directory or the directory is needed to
  satisfy the file create.
- Keep expected-target scope enforcement for unrelated directories.

## Acceptance

- `Create docs/notes with talos.mkdir, then create docs/notes/file.md` permits
  `talos.mkdir` for `docs/notes`.
- The final outcome is not partial solely because the directory create was
  correctly requested.
- Unrelated `talos.mkdir` paths remain blocked before approval.
- Tests cover Qwen-shaped mkdir plus write, GPT-OSS-shaped mkdir-only, and an
  unrelated directory attempt.

## Evidence

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- Qwen trace: `trc-2f577682-4414-448a-98f7-73bb40a225e5`
- GPT-OSS trace: `trc-6aed4ebe-2d2c-482b-ae14-76bd4e2d262a`

## Non-Goals

- No delete support.
- No broad target extraction rewrite beyond directory-parent semantics.
- No weakening sandbox or protected path policy.
