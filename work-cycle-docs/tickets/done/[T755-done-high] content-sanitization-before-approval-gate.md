# T755 - Content Sanitization Before The Approval Gate

Status: done - completed in wave 2; see completion evidence section
Severity: high
Release gate: yes (trust-surface doctrine: approved bytes must equal written bytes)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

ContentSanitizer (markdown-commentary stripping) ran inside
`FileWriteTool.execute()` and `FileEditTool.execute()` - AFTER the user
approved the mutation. The approval window previewed the raw model payload;
the bytes written to disk could differ (lexical heuristics, logged only at
debug). Written bytes ≠ approved bytes is the worst doctrine violation found
by the 2026-06-10 top-tier evaluation (roadmap item W2.1): AGENTS.md treats
"approved mutation" integrity as a P0 property.

Aggravating factor discovered during planning: `ContentSanitizer.sanitize`
is NOT idempotent - stripping shifts the trailing scan window
(`findTrailingFence` scans only the last max(2000, len/5) chars), so a
"defense-in-depth" second pass can strip an earlier legitimate fence and
re-break the invariant. The fix must therefore be move-once, not
sanitize-twice.

## Architectural Hypothesis

TurnProcessor already owns a call-normalization sequence
(ProtectedPathAliasNormalizer → ExactLiteralWriteCallCorrector →
PathArgumentCanonicalizer), each step replacing the immutable ToolCall with
a corrected copy and recording a trace event. Sanitization belongs in that
sequence: one transformation point before validation, preview, trace,
checkpoint, and execution - identical bytes everywhere by construction.

## Architecture Metadata

Capability: write/edit content hygiene (pre-approval)
Operation(s): write, edit
Owning package/class: `dev.talos.runtime.MarkdownCommentaryCallNormalizer`
(call replacement), `dev.talos.tools.ContentSanitizer` (lexical strip, moved
from tools.impl, now public), `dev.talos.runtime.TurnProcessor` (sequencing)
New or changed tools: FileWriteTool/FileEditTool lose in-tool sanitization
(write exactly the received bytes)
Risk, approval, and protected paths: approval preview now shows the payload
that will be written; no change to approval/permission policy
Checkpoint, evidence, verification, and repair: checkpoint and
ContentVerifier now operate on the same bytes the user approved
Outcome and trace: new `TOOL_CONTENT_SANITIZED` trace event
(key, strippedChars, before/after hash+bytes+lines - summaries only);
APPROVAL_REQUIRED content hashes now describe sanitized bytes
Refactor scope: the named files; no broader TurnProcessor restructuring

## Required Behavior

1. Sanitization runs once in TurnProcessor's normalization sequence, BEFORE
   the exact-literal corrector: the corrector is the runtime-owned ground
   truth for exact-write contracts and, running after, restores the user's
   literal payload even if sanitization touched it - exactness always wins.
2. Approval preview, pre-approval validation, approval trace hashes,
   checkpoint, and tool execution all see the sanitized call.
3. Tools write received bytes verbatim.
4. Sanitization is trace-recorded (redacted summaries only).
5. An edit whose new_string sanitizes into old_string is rejected by the
   existing pre-approval no-op check, before any approval prompt.

## Non-Goals

- No change to ContentSanitizer's strip heuristics (pinned by its 21 tests).
- No approval-window layout changes (T756 adds the diff block).
- No batch-tool sanitization (operations_json path is separate; follow-up
  if live evidence shows the same commentary pattern there).

## Tests / Evidence

- `MarkdownCommentaryCallNormalizerTest` - key selection/preservation,
  alias keys, .md exemption, accounting, pass-throughs.
- `TurnProcessorTest`: approved-bytes == written-bytes with trace hash
  equality (TOOL_CONTENT_SANITIZED.afterHash == APPROVAL_REQUIRED.contentHash);
  approval detail shows sanitized byte/line counts and no commentary;
  edit sanitized-into-noop rejected pre-approval (approvals == 0);
  exact-literal contract restores the literal payload.
- `FileWriteToolTest`/`FileEditToolTest` - content applied verbatim.
- E2E scenario `88-trailing-commentary-stripped-before-approval.json` -
  approval detail describes sanitized payload (40 bytes, 2 lines); written
  file has no commentary; scenario 47 (fenced write) stays green.
- Guard-pin updates: `SensitiveLogRedactionTest` and `SafetyOwnershipTest`
  now pin that the tools no longer sanitize or log (the old debug-log
  redaction pins are obsolete - there is nothing to log).

## Known Risks

- ContentSanitizer false positives now alter approved bytes (previously they
  altered written bytes silently). Strictly better: the change is visible in
  the preview and trace. The .md exemption and conservative markdown
  detection bound the false-positive surface.
- Exact-literal contracts: sanitize-then-correct ordering means a stripped
  literal payload is restored by the corrector and both transformations are
  trace-visible.

## 2026-06-11 completion evidence

- `gradlew test e2eTest` green (4,826 unit tests incl. 7 new normalizer
  tests + 4 new TurnProcessor tests; scenario 88 added to the v1 pack).
- ContentSanitizer moved to `dev.talos.tools` (public) with the
  sanitize-once contract documented; ContentSanitizerTest moved unchanged.
- FileWriteTool/FileEditTool no longer reference the sanitizer or slf4j.
