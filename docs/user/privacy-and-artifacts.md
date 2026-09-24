# Privacy and artifacts

Talos is local-first, but local-first is not the same as complete secret handling.

This page describes the current privacy boundary. It is deliberately conservative. Talos keeps execution local by default, but model prompts, traces, debug captures, command summaries, and indexes are still local artifacts that need to be handled like project evidence.

Secret redaction is best-effort. It covers common key=value secret shapes, known canaries, common standalone token prefixes, AWS access-key shapes, JWT-like tokens, PEM private-key blocks, and URL or connection-string userinfo in model context and durable sinks. It is not complete secret, PII, or credential detection.

run_command stdout and stderr pass through the model-context handoff boundary. Non-sensitive command output can be visible to the model for verification answers. Command output that required secret redaction is withheld from model context and replaced with a bounded notice.

Chat model endpoints are localhost-gated by default. Non-localhost configured chat endpoints are rejected unless the backend is explicitly configured to allow remote use. If remote chat is explicitly allowed, full prompts can leave this machine.

Local traces and logs are durable evidence artifacts, but they are not tamper-evident.

On Windows, local secret-store custody uses the current Windows user account. This is not hardware-backed custody and does not protect against a same-user process that can ask Windows to unprotect the key.

Windows trailing-dot and trailing-space path aliases are canonicalized before protected-path matching; this is not a complete Windows path-security proof.

## Practical handling

The following comparison behavior is Unreleased. Model-context permission and raw artifact permission remain separate. When model access is permitted but raw artifacts are denied, managed llama.cpp output is drained without being saved by Talos. Earlier log bytes are not changed by this suppression, but subsequent raw server diagnostics remain unavailable for that manager lifetime, including replacement processes. A later launch still resets its per-port log under the existing lifecycle. Configured native file or prompt-directory logging, unavailable capture control and connect-only llama.cpp prevent that restricted request from being sent. No logging option is silently changed.

This controls Talos-owned capture and projections. External provider retention and arbitrary endpoint ownership are not certified. The restriction must not be treated as permission to send content to a different provider.

- Do not run release audits inside folders containing real secrets.
- Use fixture secrets and canaries when testing redaction.
- Keep prompt-debug captures, provider bodies, server logs, and manual transcripts out of public commits unless a release runbook asks for a sanitized packet.
- Inspect artifact roots after privacy-sensitive tests with the project canary scanner when available.
