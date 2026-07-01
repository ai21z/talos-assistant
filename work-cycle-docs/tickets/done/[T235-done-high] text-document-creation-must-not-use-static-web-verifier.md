# T235 - Text Document Creation Must Not Use Static Web Verifier

Status: done
Priority: high

## Evidence Summary

Source audit:

- `local/manual-testing/user-perspective-broad-audit-20260511-080320/FINDINGS-USER-PERSPECTIVE-BROAD-AUDIT.md`
- Qwen transcript: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:657-664`
- GPT-OSS transcript: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:656-663`

Observed behavior:

```text
User asked Talos to create docs/synthwave-webpage-plan.md.
Talos wrote the Markdown file.
Runtime then marked the turn failed with:
"Static verification failed - web coherence could not be checked because the
workspace does not expose a small HTML/CSS/JS surface."
```

Expected behavior:

```text
Plain supported text/document artifact creation should use target/readback
verification unless the current task is actually a static web task.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION_SCOPE`

Secondary buckets:

- `TASK_CONTRACT`
- `OUTCOME_TRUTH`

## Goal

Text document creation, such as `.md` planning documents under `docs/`, should
complete with a truthful readback-style outcome when the expected target was
written and no task-specific verifier applies.

## Acceptance Criteria

- Creating `docs/synthwave-webpage-plan.md` in a workspace that also contains
  `index.html`, `styles.css`, and `script.js` does not invoke/fail the static
  web verifier.
- Static web verifier still runs for actual web app creation/update tasks that
  target `index.html`, CSS, and JavaScript files.
- Changed-files summary records the Markdown creation as readback-passed or
  completed-unverified, not failed.
- Tests cover both the plain Markdown creation path and the static web path.

## Completion Notes

- Static web capability selection now ignores explicit non-web mutation targets,
  so Markdown/text artifact creation is handled by target/readback verification.
- Web-form detection no longer treats `format` as a form task.
- Added verifier, capability-selection, and execution-outcome regression tests.
- Verification: `.\gradlew test`.

## Non-Goals

- No weakening of static web verification for actual web tasks.
- No new document generation formats.
- No PDF/DOCX support.

## Suggested Tests

- Unit/integration: task contract for `Create docs/foo.md ...` derives a
  non-web verifier profile.
- Executor test: Markdown creation in a mixed web workspace produces
  readback-passed outcome.
- Regression: static landing page creation still runs `StaticTaskVerifier`.
