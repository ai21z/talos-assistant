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

## Implementation State

Implemented and left open for review.

Report:

- `work-cycle-docs/reports/t834-strong-redaction-model-context-and-durable-sinks.md`

Implementation summary:

- Added safety-owned strong secret-shape detectors used by
  `ProtectedContentSanitizer.sanitizeText(...)`.
- Kept safety sink replacement as `[redacted]`.
- Kept `core.security.Redactor` replacement as `[secret]`.
- Made Redactor custom `redact.secrets` patterns additive with built-ins.
- Added red-first and real-path tests for standalone sanitizer, formatter,
  model-context handoff, JSON session persistence, trace redaction, and
  `RetrieveTool` as a direct lower-layer sanitizer caller.

## Non-Goals

- Do not add OCR, RAG breadth, or private-document capability expansion.
- Do not claim PII detection completeness.
