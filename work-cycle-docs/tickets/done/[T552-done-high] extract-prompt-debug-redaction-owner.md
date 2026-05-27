# [T552-done-high] Extract Prompt-Debug Redaction Owner

Status: done
Priority: high
Date: 2026-05-27
Branch: `T552`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `72dcf43b`
Predecessor: `T551`

## Scope

T552 implements the next slice selected by T551:

```text
[T552] Extract prompt-debug redaction owner
```

The scope is intentionally narrow. It extracts prompt-debug message and
provider-body redaction mechanics out of `PromptDebugInspector` without
changing prompt-debug rendering, provider-body JSON formatting, trace capture,
artifact persistence, prompt-debug capture lifecycle, or canary scanning.

## What Changed

- Added `dev.talos.cli.prompt.PromptDebugRedactor`.
- Kept `PromptDebugInspector` as the public prompt-debug formatting facade.
- Kept the public redaction constants on `PromptDebugInspector` for existing
  call sites and tests.
- Moved these redaction responsibilities behind the new redactor:
  - protected native tool result ID discovery;
  - structured-message protected/private content redaction;
  - protected assistant-answer redaction after protected read requests;
  - provider-body JSON traversal;
  - compat JSON-string tool-call argument parsing;
  - fallback provider-body text redaction;
  - final protected/private sanitizer pass.
- Added `PromptDebugInspectorRedactionOwnershipTest` to make the ownership
  split explicit.

## Preserved Behavior

These outputs are intentionally unchanged:

- prompt-debug markdown headings and message layout;
- provider-body pretty-printed JSON shape;
- public `PromptDebugInspector.format(...)`;
- public `PromptDebugInspector.redactedProviderBodyJson(...)`;
- protected tool result marker:
  `[protected tool result redacted by prompt-debug policy]`;
- protected assistant answer marker:
  `[protected assistant answer redacted by prompt-debug policy]`;
- private document canary redaction through the existing sanitizer;
- `/prompt-debug save` artifact behavior.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" --no-daemon
```

The test failed because `PromptDebugRedactor` did not exist yet.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" --no-daemon
```

The test passed after extracting the redactor and delegating from
`PromptDebugInspector`.

## Focused Regression Coverage

The focused prompt-debug and artifact canary tests were run in one Gradle
invocation to avoid parallel writes to the same Jacoco/test-result outputs:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorPrivateDocumentTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorContextLedgerTest" `
  --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" `
  --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" `
  --no-daemon
```

This verifies protected path parity, private document canary redaction, context
ledger rendering, `/prompt-debug` save behavior, and the generated-artifact
canary scanner.

## Not Changed

T552 deliberately does not move:

- `PromptDebugCapture`;
- `PromptDebugSnapshot`;
- `PromptDebugCommand`;
- `TraceRedactor`;
- `LocalTurnTraceCapture`;
- `ArtifactCanaryScanner`;
- `ToolContentMetadata`;
- `PrivateDocumentContentPolicy`;
- trace persistence through `SessionStore` / `JsonSessionStore`;
- provider prompt capture lifecycle in the LLM/client layers.

## Review Notes

The first focused-test attempt ran multiple separate Gradle `test` invocations
in parallel against the same worktree. That caused file-lock failures around
Jacoco/test-result outputs. The root cause was command orchestration, not a
code assertion failure. The focused tests were rerun sequentially in a single
Gradle invocation and passed.

## Acceptance Criteria

- Redaction owner exists in `dev.talos.cli.prompt`.
- `PromptDebugInspector` no longer owns Jackson provider-body traversal.
- `PromptDebugInspector` no longer directly imports `ProtectedContentPolicy`
  or `TraceRedactor`.
- Existing public prompt-debug facade methods remain stable.
- Existing redaction strings remain exact.
- Focused prompt-debug and artifact-canary tests pass.
- Full local check passes.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" --no-daemon
.\gradlew.bat test `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorProtectedPathParityTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorPrivateDocumentTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorContextLedgerTest" `
  --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" `
  --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" `
  --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T552 is integrated, inspect the post-extraction prompt-debug evidence
shape before selecting T553. Do not assume the next ticket should move
prompt-debug capture lifecycle or trace persistence; both remain broader than
this redaction-owner slice.
