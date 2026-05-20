# Audit Dependency Matrix - 2026-05-20

## Scope

Agent C report lane only. This report classifies the audit/evidence tickets
`T280`, `T284`, `T286`, `T306`, `T312`, `T313`, and `T319` against current
implementation blockers `T307`, `T322`, `T323`, and `T325`.

No live audit was run for this report. This is a dependency/runbook matrix based
on existing ticket and report evidence.

## Branch, Commit, Version Evidence

```text
Branch: v0.9.0-beta-dev
Starting commit: b6552f09
Candidate version: talosVersion 0.9.9
Evidence commands inspected:
  git branch --show-current
  git rev-parse --short HEAD
  gradle.properties talosVersion
```

Confidence: high for branch, commit, and version because they were inspected
from the local checkout before this report was written.

## Classification Buckets

```text
safe redirected stdin
  Non-approval prompts and installed-product smoke/probe runs where queued input
  cannot be consumed as a fake approval or next user request.

SYNC_REQUIRED
  Approval-sensitive prompts that require the synchronized Java approval harness,
  synchronized process driver, or an equivalent prompt-aware input path. Plain
  TalosBench piped approval input is exploratory only and must not be release
  evidence.

manual true PTY
  Interactive terminal/JLine/ConPTY behavior requiring a real terminal
  transcript or a dedicated PTY harness. Redirected stdin/stdout process evidence
  is not true PTY coverage.

known-blocked by implementation
  Prompts whose pass/fail meaning depends on unresolved implementation tickets:
  T307, T322, T323, or T325. These may be run as exploratory failure capture, but
  must not be used as release-ready pass evidence until the blocker is fixed and
  rerun.
```

## Current Implementation Blockers

| Blocker | Blocking surface | What it blocks |
|---|---|---|
| `T307` | mutation semantic verification beyond exact edits | Broad mutation success claims where exact replacement, append-line, bullet-count, preserve-rest, text-only per-source source-derived coverage, or static selector checks do not prove the requested semantics. The 2026-05-20 text-only per-source verifier slice reduces this blocker but does not close the broader ticket. |
| `T322` | exact three-file static web convergence | Full frontend prompts requiring exactly `index.html`, `style.css`, and `script.js`, correct linking, no `styles.css`/`scripts.js` drift, and correct verifier profile selection. |
| `T323` | office document multi-source report verification | Valid PDF/DOCX/XLS/XLSX multi-source report tasks where every readable source must be extracted, represented, and verified per source. |
| `T325` | Python command boundary and audit assertions | Python execution/test requests, pytest claims, algorithmic correctness claims, and audit cases that must fail when expected Python files are missing. The 2026-05-20 deterministic command-boundary slice covers unsupported Python command classification and final-answer suppression; the expected-file audit assertion and fresh mini-audit remain. |

## Ticket Classification Matrix

| Ticket | Primary lane | Can be audited now | Must wait for implementation blockers |
|---|---|---|---|
| `T280` two-model live audit before beta | mixed: safe redirected stdin, SYNC_REQUIRED, manual true PTY, known-blocked | Backend/profile smoke, no-approval read-only prompts, no-approval native-tool probes, unsupported-capability honesty, protected-read denial paths, non-approval document extraction honesty, and artifact canary scan plumbing can be audited now. | Full release-ready prompt-bank evidence must not treat `T307`, `T322`, `T323`, or `T325` scenarios as passed until those blockers are fixed and rerun. Approval-sensitive cases require synchronized evidence; true terminal rendering requires manual PTY evidence. |
| `T284` live two-model audit execution results | mixed evidence result lane | The results report can record present PASS/BLOCKED/SYNC_REQUIRED/manual-required outcomes from safe runs without waiting for implementation fixes. | Final pass/fail release conclusions for prompt groups covered by `T307`, `T322`, `T323`, and `T325` must wait. It must not convert smoke or exploratory redirected-approval evidence into full live-audit completion. |
| `T286` two-model backend setup for release audit | safe redirected stdin | Preflight, stale-server cleanup, isolated config generation, model-forced smoke prompts, installed command startup, `/status`, `/status --verbose`, prompt-debug availability, `/last trace`, and artifact canary scan wiring can be audited now. | Not directly blocked by `T307`, `T322`, `T323`, or `T325` for setup/smoke. It becomes blocked only when claiming full prompt-bank semantic pass coverage. |
| `T306` synchronized approval live audit runner | SYNC_REQUIRED plus manual true PTY | Scripted synchronized approval harness scenarios and synchronized redirected-process smoke can be audited now. Existing approval-denial, approval-grant, checkpoint, protected-read, document handoff, native workspace-operation, and artifact-bundle behavior remain valid lanes when rerun cleanly. | Full prompt-bank integration must wait or mark blocked for scenarios depending on `T307`, `T322`, `T323`, or `T325`. True JLine/ConPTY terminal behavior remains manual true PTY unless a real PTY harness is added. |
| `T312` full prompt-bank native-tool coverage | safe redirected stdin for non-approval; SYNC_REQUIRED for approval | Documentation coverage guards, TalosBench validation, non-approval installed-product probes, command-profile rejection probes, and deterministic synchronized native-tool coverage can be audited now. | Approval-sensitive TalosBench cases are `SYNC_REQUIRED` by default. Full native-tool audit language must exclude or block any scenario whose success depends on `T307`, `T322`, `T323`, or `T325`. |
| `T313` TalosBench piped approval drift | SYNC_REQUIRED | The fail-closed behavior itself can be audited now: approval-sensitive TalosBench cases should return `SYNC_REQUIRED` unless exploratory `-AllowPipedApprovalInputs` is explicitly supplied. Non-approval redirected-stdin cases remain usable. | Not directly blocked by `T307`, `T322`, `T323`, or `T325`; it is an evidence-integrity blocker. Any full prompt-bank release result still depends on routing approval cases through synchronized/manual evidence and blocking unresolved implementation scenarios. |
| `T319` blended manual audit scenario bank | manual true PTY plus SYNC_REQUIRED; partly known-blocked | The scenario bank and grading worksheet can be expanded now. Blended read-only, unsupported-format honesty, protected-read denial, approved-read local-display, prompt-debug, trace, and artifact hygiene flows can be audited now. | Blended flows that require exact three-file static web convergence, valid office multi-source report verification, Python execution/pytest truthfulness, or broader semantic mutation proof must wait for `T322`, `T323`, `T325`, and relevant `T307` slices before being counted as release-ready passes. |

## What Can Be Audited Now

The following are useful now and do not require waiting for `T307`, `T322`,
`T323`, or `T325`, provided each run uses a fresh audit directory and records
evidence:

- Two-model backend preflight and model-forced smoke through isolated configs.
- Installed `talos` startup, `/status`, `/status --verbose`, `/last trace`,
  `/prompt-debug last`, and prompt-debug save/provider-body availability.
- Safe redirected-stdin TalosBench cases with no approval input.
- TalosBench validation and self-test for prompt-bank structure.
- Native-tool coverage documentation guards and deterministic coverage tests.
- Command-profile boundary probes where the expected result is an honest
  bounded-profile rejection, not arbitrary Python/shell execution.
- Protected-read denial and approved-read behavior through the synchronized
  approval harness.
- Private-document local-display-only and explicit send-to-model handoff
  scenarios already represented in synchronized approval lanes.
- Artifact bundle integrity: final answer, approval transcript, trace,
  prompt-debug/provider-body capture where available, session/turn artifacts,
  final workspace diff, and canary scan result.
- Manual true PTY packet preparation and validation, as long as it is labelled
  `MANUAL_REQUIRED` until a completed true-terminal transcript is captured.

## What Must Wait

The following must be marked blocked, not passed, until the named implementation
ticket is fixed and the audit is rerun from fresh fixtures:

- `T307`: mutation tasks whose requested semantics are not covered by an
  existing deterministic verifier. Readback-only must not become a success
  claim for semantic correctness. The text-only per-source source-derived
  verifier slice is now covered, but broader semantic rewrites remain blocked.
- `T322`: realistic frontend creation/repair prompts requiring exactly
  `index.html`, `style.css`, and `script.js`; no sibling drift; correct links;
  and correct static verifier profile.
- `T323`: valid office multi-source report prompts where every readable
  PDF/DOCX/XLS/XLSX source must be extracted and represented in the generated
  report.
- `T325`: Python execution/test prompts and algorithmic correctness claims,
  including cases that request pytest or other unsupported execution. The
  deterministic no-Python-execution wording is now implemented; audit cases that
  require expected Python output files must still fail when those files are
  absent.

Exploratory runs against these areas may be useful to capture fresh failures,
but the expected audit outcome is `known-blocked by implementation`, not
release-ready pass.

## Next Big Audit Artifact Checklist

For the next broad audit packet, align the artifact set with `AGENTS.md` and
keep model-specific roots separate:

- Exact user prompt for every natural-language turn.
- Talos final answer.
- `/last trace` after every natural-language assistant response.
- `/prompt-debug last` and `/prompt-debug save` when prompt construction,
  tool-surface, provider-body, approval, privacy, or failure-truth claims matter.
- Provider-body JSON where required by the runbook or finding.
- Approval prompt, approval acceptance, approval denial, remember-approval, or
  `SYNC_REQUIRED` evidence for every approval-sensitive case.
- Command output and verifier output when commands or verification are part of
  the claim.
- Final workspace `git status --short` for each fixture workspace.
- Final workspace diff for each fixture workspace.
- Final file state for every changed expected target and every high-risk
  similar target such as `script.js` versus `scripts.js`.
- Session/turn artifacts when the finding depends on persistence, redaction, or
  prompt-debug/provider-body behavior.
- Artifact scan roots, with exact command and allowlist rationale:
  `local/manual-testing/<audit-id>` and
  `local/manual-workspaces/<audit-id>` for live/manual runs.
- Explicit bucket per case:
  `safe redirected stdin`, `SYNC_REQUIRED`, `manual true PTY`, or
  `known-blocked by implementation`.
- Explicit model/backend/profile identity:
  `qwen2.5-coder:14b` and `gpt-oss:20b` where used, preferred managed
  `llama.cpp`, and any isolated config path.
- Branch, commit SHA, candidate version, executable path, and whether the
  candidate was clean-built and clean-installed before invocation.

## Bottom Line

The next audit should proceed in lanes instead of treating the prompt bank as a
single binary gate. Backend setup, safe redirected-stdin prompts, synchronized
approval harness coverage, and manual PTY packet validation can continue now.
Release-ready pass claims for semantic mutation, exact three-file static web,
office multi-source reports, and Python execution/test truthfulness must wait
for `T307`, `T322`, `T323`, and `T325` respectively.
