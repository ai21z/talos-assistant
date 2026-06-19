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

Talos's deterministic no-change/no-success correction is strongest for file-mutation turns; `run_command` claims and read/answer factual claims are not yet equivalently covered.

Secret redaction currently catches common key=value secret shapes and known canaries; it does not yet detect standalone API tokens, JWTs, PEM private-key blocks, connection strings, or high-entropy blobs. Talos redacts common key=value secret shapes (api_key=, password:, token=) and known canaries from model context, logs, traces, and persisted artifacts. It does NOT yet detect bare credentials with no assignment syntax: standalone API tokens, JWTs, PEM private-key blocks, secrets embedded in URLs/connection strings, or high-entropy blobs. Do not rely on Talos to scrub such values from files it reads, command output, sessions, or traces.

`run_command` stdout and stderr are not withheld from model context by default. Command output (run_command stdout/stderr) is passed to the model after best-effort textual redaction of recognizable secret-assignment patterns and known markers only. It is NOT withheld by default and is not classified by source path. Do not run commands that print real credentials in this beta.

On Windows, paths that differ only by trailing dots or spaces can bypass exact-name protected-path matching. Protected-path classification currently matches on the literal path text. On Windows, file names that differ only by trailing dots or spaces, such as `id_rsa.`, are normalized away by the OS at open time and may not be recognized as protected.

Chat model endpoints are localhost-gated by default. Non-localhost configured chat endpoints (`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is configured for that backend; when remote chat is explicitly allowed, full prompts can leave this machine. Keep chat endpoints on `127.0.0.1`, `::1`, or `localhost` to remain local-first.

The local master key is still stored beside the encrypted data, so current encryption is casual-inspection protection, not OS-backed key custody. The master key is stored on disk next to the ciphertext with no passphrase; anyone who can read `~/.talos/secrets/` can recover the stored secrets.

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
