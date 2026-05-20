# T287 - Sensitive Workspace Detector Tokenization

Status: done - sensitive workspace tokenization implemented and covered
Severity: high
Release gate: yes for private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

The warning-only sensitive workspace detector used substring matching for short terms such as `id`, causing false positives on ordinary names like `valid-project` and `grid-ui`.

## Evidence from current code

`SensitiveWorkspaceDetector` now keeps broad substring matching for longer sensitive terms and token-aware matching for short `id` signals.

## Evidence from tests/audits

`SensitiveWorkspaceDetectorTest` now covers:

- no warning for `valid-project`
- no warning for `grid-ui`
- warning for tokenized `id-documents`
- warning for `passport-renewal`
- no content reads

## User impact

False-positive privacy warnings can train users to ignore real sensitive-folder warnings.

## Product risk

Private mode becomes less credible if warning signals are noisy.

## Runtime boundary affected

Startup/workspace-inspection warning UX only. The detector remains warning-only and must not read file contents.

## Non-goals

- Do not automatically enable private mode.
- Do not inspect file contents.

## Required behavior

Short terms such as `id` must match only as path/name tokens, not as arbitrary substrings.

## Proposed implementation

Keep the current tokenized matcher and broaden tests if more short sensitive terms are added.

## Tests

`./gradlew.bat test --tests "*SensitiveWorkspaceDetector*" --no-daemon`

## Acceptance criteria

- False positives for `valid-project` and `grid-ui` stay fixed.
- Warnings still fire for tokenized ID/passport/tax/private-document signals.

## Remaining blockers

Full gate still needs live private-mode audit evidence.

## Open questions

Should future private-folder detection use a scored signal model instead of direct term matching?

## Related files

- `src/main/java/dev/talos/runtime/policy/SensitiveWorkspaceDetector.java`
- `src/test/java/dev/talos/runtime/policy/SensitiveWorkspaceDetectorTest.java`
