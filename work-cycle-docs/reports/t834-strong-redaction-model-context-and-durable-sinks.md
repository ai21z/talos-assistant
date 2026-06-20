# T834 Strong Redaction Across Model Context And Durable Sinks

Status: implemented, open for review
Branch: `v0.9.0-beta-dev`
Base commit: `31f6a148d4e22234178788314cdcf9facc6ca3fb`
Talos version: `0.10.5`

## Scope

T834 strengthens the common safety-layer sanitizer used by model-facing and
durable lower-layer sinks. It does not claim complete secret detection; the
detectors are best-effort shape guards for high-risk secret formats.

Production source changes are intentionally limited to:

- `src/main/java/dev/talos/safety/SecretShapePatterns.java`
- `src/main/java/dev/talos/safety/ProtectedContentSanitizer.java`
- `src/main/java/dev/talos/core/security/Redactor.java`

No `site/` files are in scope.

## Owner Decision

The strengthened detection belongs in `dev.talos.safety`, not only in
`runtime.policy.ProtectedContentPolicy`, because lower layers call
`ProtectedContentSanitizer` directly and cannot import `runtime.*`.

Current lower-layer direct `sanitizeText(...)` callers include:

- `core.rag.RagService`
- `core.extract.DocumentExtractionService`
- `core.extract.DocumentExtractionPreflight`
- `core.context.ConversationCompactor`
- `core.context.CompactionIntegrityPolicy`
- `tools.impl.RetrieveTool`
- `tools.impl.GrepTool`
- `safety.SafeLogFormatter`

`LayeredArchitectureTest` enforces `core_must_not_depend_on_runtime` and
`tools_must_not_depend_on_runtime`; `SafetyOwnershipTest` enforces that
`safety` imports no other Talos layer.

## Implementation

Added safety-owned best-effort detectors for:

- PEM private-key blocks, handled as a multi-line block before line-level
  processing can leak the base64 body;
- connection-string userinfo, including JDBC-style
  `jdbc:postgresql://user:password@host/db`;
- broadened token prefixes: `ghp_`, `gho_`, `ghu_`, `ghs_`, `ghr_`,
  `github_pat_`, `sk-`, `sk-proj-`, `sk-ant-`, and Slack `xox*`;
- `eyJ`-anchored JWT-like tokens;
- bounded high-entropy tokens with length, character-class, entropy, git-SHA,
  and UUID negative checks.

`ProtectedContentSanitizer.sanitizeText(...)` now applies these detectors after
existing key/value assignment redaction and before canary/private-document fact
redaction. It keeps the existing safety mask: `[redacted]`.

`core.security.Redactor` now imports the safety pattern definitions for the
line-compatible built-ins and treats configured `redact.secrets` patterns as
additive rather than replacing all built-ins. It keeps the existing Redactor
mask: `[secret]`. T834 does not force Redactor to emit `[redacted]`.

PEM block and bounded high-entropy logic remain safety-sink behavior in this
ticket because `Redactor.redactBlock(...)` is deliberately line-preserving and
currently applies `redactLine(...)` line by line. Sharing those behaviors into
Redactor without changing its API is left out of scope.

## Red-First Evidence

The new tests were added before production changes. The first focused run
failed as expected:

- `ProtectedContentSanitizerTest.redactsBareSecretShapes`
- `ToolResultFormatterTest.sanitizesBareSecretShapesUnlessPreservationIsRequested`
- `ToolResultModelContextHandoffTest.ordinaryModelContextHandoffRedactsBareSecretShapes`
- `JsonSessionStoreTest.savedSessionRedactsBareSecretShapes`
- `TraceRedactorTest.redactsBareSecretShapes`
- `RetrieveToolTest.retrieve_redactsBareSecretShapesThroughDirectSafetyCaller`
- `RedactorTest.BadRegexHandling.invalid_regex_in_config_is_skipped_not_thrown`
- `RedactorTest.BadRegexHandling.custom_secret_patterns_are_additive_with_builtins`

The failure mode was the intended gap: bare secret shapes still appeared in
model-facing or persisted output, and Redactor custom `redact.secrets` config
dropped built-ins.

## Green Evidence

After implementation, the same focused set passed:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.ProtectedContentSanitizerTest" --tests "dev.talos.runtime.toolcall.ToolResultFormatterTest" --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.trace.TraceRedactorTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.core.security.RedactorTest" --rerun-tasks --no-daemon
```

Additional focused ownership/architecture verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.safety.*" --tests "dev.talos.core.security.RedactorTest" --tests "dev.talos.runtime.toolcall.ToolResultFormatterTest" --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.trace.TraceRedactorTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --tests "dev.talos.architecture.LayeredArchitectureTest" --no-daemon
```

The first full `check` found an over-redaction regression in prompt-audit
memory-retention strings: the high-entropy candidate regex allowed internal
`=`, so `rawTurnMessagesEvictedWithoutSketch=20` was treated as one candidate.
The detector was tightened to allow `=` only as trailing base64 padding.
Targeted rerun of the two failed tests plus `ProtectedContentSanitizerTest`
passed.

Final gates passed:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
```

## Non-Claims

- T834 does not make Talos a complete secret detector.
- T834 does not change `tools.ContentSanitizer` or the file-write
  approved-bytes invariant.
- T834 does not change `run_command` model handoff policy; T837 remains open.
- T834 does not change master-key custody; T838 remains open.
- T834 does not change Windows protected-path canonicalization; T836 remains
  open.
