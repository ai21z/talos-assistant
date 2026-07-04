# [T953-open-low] Terminal fallback should not render narrow no-break spaces as question marks

Status: open
Priority: low

## Evidence Summary

- Source: installed-product T929 GPT-OSS manual PTY observation plus hidden
  session artifact inspection
- Date: 2026-07-04
- Talos version / commit: 0.10.8 /
  6c77d4b83f4d653a18beb94db2b33c8a244885a9
- Branch: `v0.9.0-beta-dev`
- Model/backend: `llama_cpp/gpt-oss-20b`
- Workspace fixture:
  `local/manual-workspaces/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/gptoss/manual-pty`
- Durable session evidence:
  `local/manual-testing/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/artifacts/gptoss/manual-pty/isolated-home/.talos/sessions/04b09b035797023c5ddc0a7d6ed5b9f802f5d53d-20260704120937.turns.jsonl`
- Raw transcript path:
  `local/manual-testing/t929-0.10.8-6c77d4b8-full-audit-20260704-1346/artifacts/gptoss/manual-pty/TRANSCRIPT.md`
  did not capture the full JLine answer pane, so terminal rendering evidence is
  from the live observed PTY output.
- Verification status: functional behavior passed; terminal fallback polish
  defect observed

Redacted prompt sequence:

```text
/privacy private on
Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name.
n
```

Expected behavior:

```text
If Talos has to downgrade model-authored Unicode punctuation for a terminal,
spacing punctuation such as narrow no-break space should fall back to plain
ASCII space, not `?`.
```

Observed behavior:

```text
The GPT-OSS denial answer rendered in the live PTY as:

  I'm unable to view the text of the?`medical-notes.docx`?file ...

The durable session artifact shows the underlying answer contained narrow
no-break spaces around the inline code span and a non-breaking hyphen in the
filename:

  I’m unable to view the text of the `medical‑notes.docx` file ...

`Sanitize.toAsciiFallback` maps U+00A0 to space and U+2011 to hyphen, but does
not map U+202F NARROW NO-BREAK SPACE, so fallback output can display `?`.
```

## Classification

Primary taxonomy bucket: `AUDITABILITY`

Secondary buckets:

- `OUTCOME_TRUTH`
- `UNSUPPORTED_CAPABILITY`

Blocker level: candidate follow-up

Why this level:

```text
This is not a privacy leak, mutation bug, or false success. It is a terminal
rendering polish defect on the approval/private-doc evidence path. It should be
fixed before public artifacts if time permits, but it is lower priority than
the evidence-packaging blockers.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The terminal fallback map handles common punctuation but lacks several
space-like Unicode code points emitted by local models. When the terminal path
chooses ASCII fallback, unsupported spacing code points degrade to `?`.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/util/Sanitize.java`
- `src/test/java/dev/talos/core/util/SanitizeTerminalOutputTest.java`
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`

Why a one-off patch is insufficient:

```text
This is a fallback policy gap, not a GPT-OSS-only answer issue. Add the
space-like code points to the terminal fallback table and pin them in tests.
```

## Goal

```text
Terminal ASCII fallback maps U+202F and similar spacing punctuation to normal
ASCII spaces so model-authored inline formatting stays readable.
```

## Non-Goals

- No changing model prompts.
- No changing protected-document privacy behavior.
- No broad terminal rendering redesign.
- No treating this as a launcher encoding regression; T880 remains closed.

## Implementation Notes

```text
Start with a failing `SanitizeTerminalOutputTest` using the exact stored answer
shape:

  I’m unable to view the text of the\u202f`medical\u2011notes.docx`\u202ffile

ASCII fallback should produce readable spaces/hyphens, not `?`.
```

## Architecture Metadata

Capability:

- CLI answer rendering

Operation(s):

- render terminal output

Owning package/class:

- `dev.talos.core.util.Sanitize`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: low
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: terminal output contains no replacement-question
  artifacts for common spacing punctuation
- Verification profile: unit test plus affected manual PTY lane if time allows
- Repair profile: no model reprompt change

Outcome and trace:

- Outcome/truth warnings: final-answer meaning must stay unchanged
- Trace/debug fields: unchanged

Refactor scope:

- Allowed: extend ASCII fallback table for spacing code points
- Forbidden: broad output sanitizer rewrite

## Acceptance Criteria

- `Sanitize.toAsciiFallback` maps U+202F to a normal ASCII space.
- The exact GPT-OSS answer shape no longer contains `?` around the inline file
  path after fallback.
- Existing Unicode-safe behavior remains unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `SanitizeTerminalOutputTest` covers U+202F around inline code and
  U+2011 in filenames.
- Integration/executor test: not required.
- JSON e2e scenario: not required.
- Trace assertion: not required.

Manual rerun:

- Prompt family: GPT-OSS private-document denial PTY lane.
- Workspace fixture: generated `manual-pty` fixture.
- Expected trace: denial still recorded.
- Expected outcome: answer pane contains no `?` replacement around the filename.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.util.SanitizeTerminalOutputTest" --no-daemon
```

## Known Risks

- Mapping too many arbitrary code points could hide genuinely unsupported
  symbols. Limit this to space-like punctuation with clear ASCII equivalents.

## Known Follow-Ups

- Add additional spacing punctuation only when observed in real terminal output.
