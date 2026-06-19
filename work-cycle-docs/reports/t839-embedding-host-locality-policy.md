# T839 Embedding Host Locality Policy

Status: open for review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Started from: `dba83275b8f1cf702d19a5d7f104f7c093f49f5e`
Implementation commit: pending closeout

## Decision

T839 applies the T835 URI-based locality policy to embedding transports. The
pre-T839 embedding clients used substring checks for loopback detection, which
accepted remote lookalike hosts such as `http://127.0.0.1.evil.example:11434`.

The fix preserves the existing explicit remote opt-ins:

- `ollama.allow_remote=true` for the Ollama `EmbeddingsClient`.
- `embed.allow_remote=true` for the OpenAI-compatible `CompatEmbeddingsClient`.

## Production Changes

- Renamed `dev.talos.core.ChatHostLocalityPolicy` to
  `dev.talos.core.HostLocalityPolicy` because the policy now owns model and
  embedding transport locality, not chat only.
- Updated Ollama and llama.cpp chat providers to use the neutral policy name
  while preserving their existing default-deny and explicit remote opt-in
  behavior.
- Updated `EmbeddingsClient` to enforce URI-based host locality through
  `HostLocalityPolicy` instead of the substring `isLocalhost(...)` helper.
- Updated `CompatEmbeddingsClient` to enforce the same policy and removed its
  substring `isLocalhost(...)` helper.

## TDD Evidence

Observed red run before production changes:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.core.embed.CompatEmbeddingsClientTest" --tests "dev.talos.core.ChatHostLocalityPolicyTest" --no-daemon
```

The run failed exactly on the new lookalike-host assertions:

- `EmbeddingsClientSecurityTest > lookalikeLoopbackHostBlocked()`
- `CompatEmbeddingsClientTest > lookalikeLoopbackHostBlockedByDefault()`

This proved the tests caught the current substring bypass before production code
changed.

Focused green run after production changes:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.EmbeddingsClientSecurityTest" --tests "dev.talos.core.embed.CompatEmbeddingsClientTest" --tests "dev.talos.core.HostLocalityPolicyTest" --tests "dev.talos.engine.ollama.OllamaEngineProviderTest" --tests "dev.talos.engine.llamacpp.LlamaCppEngineProviderTest" --no-daemon
```

Additional green gates:

```powershell
.\gradlew.bat test --tests "dev.talos.core.embed.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

## Behavior Preserved

- Real loopback hosts continue to work.
- Explicit remote embedding opt-in continues to work.
- Existing chat locality behavior remains unchanged.
- Embedding request and response payload behavior is unchanged.

## Non-Goals

- No redaction, Windows path, `run_command`, or master-key custody fix.
- No candidate cut.
- No Qodana policy change.
- No `SetupCmd.java` change.
- No `site/` edits.
