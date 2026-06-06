# T698 - Static-Web Synchronized Fresh/Dirty Audit Packet

Status: open
Severity: high

## Problem

The latest `test02-10-post-t693-live-audit-20260605-105937` run produced useful
Qwen evidence, but it is not a complete work-cycle audit packet:

- `FINDINGS.md` is empty.
- `LIVE-AUDIT.md` is empty.
- `MATRIX.csv` contains only the header.
- The available transcript files are partial and do not capture the whole
  fresh/dirty conversation cleanly.
- GPT-OSS was not completed in the same packet.
- Gemma setup was attempted but not completed as a comparable lane.

That means the run can inform tickets, but it cannot close the static-web
fresh/dirty audit gate.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-10-post-t693-live-audit-20260605-105937/`
- Empty files:
  `FINDINGS.md`, `LIVE-AUDIT.md`
- Header-only matrix:
  `MATRIX.csv`
- Partial transcript files:
  `artifacts/qwen/SESSION-FRESH-OUTPUT.txt`,
  `artifacts/qwen/SESSION-DIRTY-OUTPUT.txt`
- Useful but incomplete evidence:
  `artifacts/qwen/prompt-debug/`,
  `homes/qwen/.talos/sessions/traces/`,
  `artifacts/qwen/fresh-final/`,
  `artifacts/qwen/dirty-final/`.

## Architecture Metadata

- Capability ownership: work-cycle/audit process; no product runtime owner.
- Operation type: installed-product live audit packet.
- Risk: high for release decisions; incomplete audit packets can make a
  partial model run look like a full evidence gate.
- Approval behavior: audit must use synchronized/manual approval evidence, not
  blind redirected approval input.
- Protected path behavior: artifact canary scan required for captured roots.
- Checkpoint behavior: capture checkpoint evidence when mutation occurs.
- Evidence obligation: exact prompt, trace, prompt-debug, final files, diffs,
  approvals, and scoring row per natural-language prompt.
- Verification profile: audit observes `STATIC_WEB`; it does not add verifier
  behavior.
- Repair profile: audit observes static-web repair continuation and target
  narrowing.
- Outcome/trace changes: none required unless the audit finds product defects.
- Allowed refactor scope: audit harness/scripts and documentation only.

## Acceptance

- A new audit root is created after T696/T697 work, using isolated homes and
  fresh workspaces.
- Qwen and GPT-OSS both run the same fresh and dirty prompt sequence.
- Optional Gemma lane is included only if setup is stable; otherwise the audit
  labels it explicitly as excluded or exploratory.
- Every natural-language prompt has:
  - exact user prompt,
  - approval evidence,
  - final answer,
  - `/last trace`,
  - `/prompt-debug last`,
  - `/prompt-debug save`,
  - final file state or diff,
  - matrix row.
- `FINDINGS.md`, `LIVE-AUDIT.md`, and `MATRIX.csv` are populated before any
  audit conclusion is claimed.
- Artifact canary scan runs for the audit root.
- The packet explicitly states whether it is release-grade or exploratory.

## Regression/Runbook Checks

- Add or update the runbook script so transcript capture cannot silently leave
  empty summary files.
- If a model lane is skipped, the report must name the lane and reason.
- If approvals are not synchronized/manual, the report must mark the run
  exploratory.

## Non-Goals

- No product-code behavior change.
- No replacement for the broader full prompt-bank audit tickets `T280`,
  `T284`, `T306`, and `T312`.

