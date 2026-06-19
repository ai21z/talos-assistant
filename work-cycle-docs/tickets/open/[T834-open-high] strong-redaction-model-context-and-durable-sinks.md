# [T834-open-high] Strong Redaction Across Model Context And Durable Sinks

Status: open
Priority: high
Type: code-fix
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Close the Wave 6 high trust gap where model-context handoff and durable sinks
use weaker key=value/canary redaction than the stronger prompt-debug redactor.

Source context:

- `work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md`
- `work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md`

## Scope

- Route model-facing and persistence sanitization through a stronger shared
  redaction path.
- Cover model-context handoff, sessions, turn JSONL, local traces, logs, grep,
  retrieve, and command output where they reuse the common sanitizer.
- Preserve honest wording that automated detection is best-effort, not a
  complete secrecy guarantee.

## Acceptance Criteria

- Bare `ghp_`, `sk-`, JWT, PEM private-key, connection-string, and high-entropy
  fixtures are redacted through the actual handoff/persistence paths, not only
  through a standalone detector unit test.
- Regression tests prove the same sanitizer used by model context and session
  persistence redacts those shapes.
- Existing key=value and canary redaction behavior remains covered.
- No docs or site claim complete secret detection.

## Non-Goals

- Do not add OCR, RAG breadth, or private-document capability expansion.
- Do not claim PII detection completeness.
