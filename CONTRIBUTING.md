# CONTRIBUTING.md (LOQ-J)

## 0) Support Matrix & Prereqs

* **Java:** 21+ (JDK with `jpackage` for MSI)
* **OS:** Windows, macOS, Linux
* **Models:** Ollama installed locally (default small: Qwen2.5-Coder-7B; embeddings: bge-m3)
* **No telemetry.** Network is **off by default**; any network use must be explicitly gated.

---

## 1) Repository Layout

```
src/main/java/dev/loqj/      # CLI entrypoint (dev.loqj.app.Main), REPL modes, commands
src/main/java/dev/loqj/core  # Indexer, Lucene store (BM25+ANN), RAG service, packing
src/main/resources/          # default-config.yaml, prompt templates
src/test/java/               # JUnit 5 tests (mirrors prod pkgs)
docs/                        # product notes, roadmaps, assessments
tools/, scripts/             # installers, helper scripts
config/                      # sample configs (do not put secrets here)
```

---

## 2) Build & Test

* Build & unit tests:
  `./gradlew clean build`  (Windows: `.\gradlew clean build`)
* Unit tests only:
  `./gradlew test`
* Install local distribution:
  `./gradlew installDist` → `build/install/loqj/bin/loqj :status`
* Package (Windows MSI):
  `./gradlew jpackageApp` (requires JDK with `jpackage`)
* **Smoke transcripts** (TTY): see `smoke_test_commands.txt`, `validation_commands.txt`, `test_commands.txt`.

**CI gate (expected to pass locally before a PR):**

* `./gradlew test` green
* REPL smoke: `:reindex --full`, `:files`, two-file comparison, Auto→Ask trivia
* `rag-ask` exit code non-zero on retrieval error (scriptable)

---

## 3) Local-First & Security Policy (must-follow)

* **Default:** offline. The CLI **must not** call the network unless the user opts in (e.g., `--web`, or explicit `connectors sync` in future).
* Secrets: only via `:secret set|get|del` (encrypted-at-rest). Never commit secrets or tokens.
* Index sandbox: respect workspace root; prevent path traversal; do not index outside root.
* Archives: excluded by default; if encountered, log a one-line **skip warning**.

---

## 4) Indexing & Retrieval Guarantees

* **Hybrid retrieval:** Lucene BM25 + HNSW vectors; vectors can be toggled in config.
* **Path semantics:** treat `\` and `/` equivalently; normalize to `/` internally.
* **Pinning:** quoted or inline paths map to workspace files; prefer full relpath, then unique basename fallback.
* **Snippet packing:** pinned files first; stable ordering; `[Sources]` list **deduped** and **chunk suffix stripped**.
* **Limits enforced:** `file_bytes_max`, `file_lines_max` applied **before** parsing/embedding; oversized files are skipped and reported.
* `:files` uses a **MatchAll** query (not `"*"`); for very large indexes, listing is paginated/streamed.
* **Health visibility:** `:status --verbose` and `:health` show index dir, doc count, last index time, vector on/off, and **last retrieval/index error**.

---

## 5) CLI UX Rules

* **TTY-aware output:** spinner and “Answering…” status only when attached to a TTY; disable when piped.
* **ASCII fallback:** use ASCII borders when Unicode is not supported (legacy Windows consoles).
* **Auto routing:** trivia/general questions → **Ask** (no sources). File-grounded questions → **RAG**.
* **Exit codes:** `rag-ask` returns non-zero on errors (missing index, retrieval failure).

---

## 6) Coding Style

* Java: 4-space indent, UTF-8, ~120-char line target, grouped imports.
* Naming: Classes/Enums `UpperCamelCase`; methods/fields `lowerCamelCase`; constants `SCREAMING_SNAKE_CASE`.
* Prefer immutable value objects; avoid shared mutable state across modes.
* Javadoc public APIs; inline comments explain **rationale**.
* Keep CLI help strings and docs in sync for any command additions/renames.

---

## 7) Tests

* Place tests under `src/test/java`, mirroring package structure; names end with `*Test`.
* **Regression must-haves:**

  * Pin normalization (`docs\landing.md` → `docs/landing.md`)
  * `[Sources]` dedup + pin-first policy
  * `:files` listing (match-all, pagination if large)
  * Auto→Ask routing for trivia (no sources)
  * Index limits (skip >N bytes / >N lines) and skip messaging
  * Corrupt index → surfaced error + rebuild hint
  * ASCII/Unicode box snapshots
* Add scripted transcripts for new CLI verbs.

---

## 8) Branching, Commits & PRs

* Work branch from and target: **`v0.9.0-beta-dev`**
* Commit style: `type: imperative summary` (`feat:`, `fix:`, `docs:`, `refactor:`).
* PR description must include: **Motivation**, **User-visible changes**, **Testing commands**, **Screenshots/Transcripts**, **Docs updated**, **Security/Config notes**.

### Definition of Done (for PRs)

* Tests added/updated and green
* `:help`/docs updated if CLI changes
* No secret leakage; network calls gated
* Smoke transcript included (copy/paste run)

---

## 9) Release Process (Beta)

* Cut release branch: `release/v0.9.0-beta`
* Ensure:

  * `:reindex --full` summary shows Scan/Embed/Skip counts
  * `:files` and `:health` work on a clean workspace
  * README has Quickstart (Ollama + `:reindex` + ask), a 60–90s GIF, **Limitations** (text/HTML by default), and **Model matrix**
* Tag `v0.9.0-beta` and publish binaries (MSI, tar.gz)
* Post changelog + checksums

---

## 10) Roadmap Notes for Contributors (Near-term)

* `:health` command (index stats + last error)
* ASCII border fallback + spinner parity (TTY only)
* Retrieval error surfacing in RAG path with “Try :reindex” hint
* Index limits enforced pre-parse; skip summary
* `rag-ask` exit codes
* (Later) Extractor SPI for PDFs/Office; connectors as **read-only sync** to local cache

---

## 11) Communication

* Use GitHub Issues with labels: `bug`, `feat`, `polish`, `docs`, `p0`, `p1`, `p2`.
* Use Discussions or a lightweight chat for Q&A; keep technical decisions in issues/PRs.

---

### Notes on AGENTS.md

If you still want an **AGENTS.md**, scope it to “how multi-agents (e.g., code review bot, planning agent) should behave in this repo.” For repo guidelines, **rename to `CONTRIBUTING.md`** (standardized; discoverable).

---

## What changed vs. Codex’s AGENTS.md (and why)

* **Renamed & refocused**: AGENTS.md → CONTRIBUTING.md (clear contributor guide).
* **Local-first/security clarified**: explicit network gating, secrets handling, archive skip policy.
* **Index/RAG guarantees**: path normalization, pin policy, `[Sources]` dedupe, limits enforcement, `:files` match-all.
* **Health & diagnostics**: explicit `:health` and surfaced errors (prevents “indexed but empty” UX).
* **TTY/ASCII**: mandated fallback and spinner discipline.
* **PR DoD & release checklist**: concrete gates so quality stays consistent.
* **Roadmap hooks**: near-term items aligned with your beta plan.
