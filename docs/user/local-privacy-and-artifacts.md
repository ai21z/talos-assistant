# Local Privacy And Artifacts

This page answers: "What data can Talos read, send to model context, or persist
locally?"

## Current Support

Talos is local-first, but local-first does not mean "nothing is ever captured."
Talos can create local runtime artifacts such as traces, prompt-debug captures,
provider-body captures, session state, command logs, indexes, and cache files.

Use private mode for sensitive workspaces:

```text
/privacy status
/privacy private on
```

## Current Trust-Surface Limits

Talos is local-first when it is configured to use local model endpoints, but the
current beta must not be described as a general private-paperwork or secret-safe
assistant.

Talos's deterministic no-change/no-success correction is strongest for file-mutation turns. It also withholds recognized ungrounded command/tool-output shapes when the turn ledger lacks the matching producer (git-status, test-run, process-list, shell listing/cat output, and explicit file-content claims without a matching read), but arbitrary `run_command` claims and broad read/answer factual claims are not completely covered.

Secret redaction is best-effort. It covers common key=value secret shapes, known canaries, common standalone token prefixes, AWS access-key shapes, JWT-like tokens, PEM private-key blocks, and URL/connection-string userinfo in model-context and durable sinks. Command-output handoff also withholds bounded high-entropy command streams before model context. This is not complete secret, PII, or credential detection. Do not rely on Talos to scrub arbitrary sensitive text from files it reads, command output, sessions, or traces.

`run_command` stdout and stderr pass through the model-context handoff boundary. Non-sensitive command output remains visible to the model for verification answers; command output that required secret redaction is withheld from model context and replaced with a bounded notice. This is not a complete command-output privacy proof. Do not run commands that print real credentials in this beta.

Windows trailing-dot and trailing-space path aliases are canonicalized before protected-path matching; this is not a complete Windows path-security proof.

Chat model endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend; when remote chat is explicitly allowed, full prompts can leave this machine. Keep chat endpoints on `127.0.0.1`, `::1`, or `localhost` to remain local-first.

On Windows, the local secret-store master key is protected at rest with DPAPI CurrentUser and is tied to the Windows user account. This is not hardware-backed custody and does not protect against a same-user process that can ask Windows to unprotect it. On non-Windows platforms, master-key custody remains unchanged and is not yet OS-backed. The current DPAPI bridge uses a non-interactive PowerShell child process; raw key bytes can cross Java/helper process pipes during protect and unprotect. Talos does not expose those bytes as a user-facing console output or trace artifact, but same-user process monitoring is still in the trust boundary. Treat `~/.talos/secrets/` and your OS account as part of the trust boundary.

Local traces and logs are durable evidence artifacts, but they are not tamper-evident. They are plaintext local diagnostics, not a signed append-only audit trail.

## Developer Mode

Developer/default mode is designed for normal code and text workspaces.

In this mode, approved direct protected reads may enter model context for the
current turn.

Do not use developer mode for private paperwork folders.

## Private Mode

Private mode changes protected-read and document-extraction handling.

In private mode:

- approved protected reads default to local-display-only
- extracted PDF/DOCX/XLS/XLSX text is local-display-only by default
- RAG/retrieve is disabled by default
- raw protected/document content persistence is off by default

Operational traces, prompt-debug captures, provider-body captures, sessions,
logs, and command output may still exist locally. Private mode narrows sensitive
content handoff; it is not a guarantee that no local operational artifacts are
created.

Private mode does not make Talos ready for tax, health, legal, family, or
administrative paperwork.

## Protected Reads

A protected read can be approved or denied. Denial is expected not to reveal
protected content.

When approved in private mode, the content is withheld from model context unless
separate config opt-ins allow otherwise.

## RAG And Indexing

RAG indexing is disabled by default in private mode. This avoids placing
protected or unsupported content into a searchable corpus without explicit
review.

## Local Artifact Types

Talos may write local artifacts for:

- turn traces
- prompt-debug evidence
- provider-body captures
- session storage
- command output
- model/cache configuration
- RAG indexes

Treat these artifacts as local evidence. Do not publish them without review.

## Good Beta Use

Good:

- code projects
- Markdown/text workspaces
- config files
- static web projects
- controlled test fixtures

Not a beta claim:

- private personal paperwork folders
- legal or medical records
- broad home directories
- folders full of secrets
