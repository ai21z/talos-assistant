# T833 Wave 6 Trust-Surface Honest Disclosure

Status: open for review
Branch: `v0.9.0-beta-dev`
Base commit: `f8e8c3065ff60d706d8342fda89101f834727cef`
Talos version: `0.10.5`
Source audit: `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`

## Summary

T833 is a Tier 0 honesty pass. It changes documentation and adds a docs
honesty test so Talos-owned trust/privacy/security/truthfulness claims describe
current code boundaries, not future intent.

T833 does not change production behavior. A documented limitation is not an
overclaim.

## Bounded Trust Claims

The docs now explicitly disclose:

- Talos's deterministic no-change/no-success correction is strongest for
  file-mutation turns; `run_command` claims and read/answer factual claims are
  not yet equivalently covered.
- Secret redaction currently catches common key=value secret shapes and known
  canaries; it does not yet detect standalone API tokens, JWTs, PEM
  private-key blocks, connection strings, or high-entropy blobs.
- `run_command` stdout and stderr are not withheld from model context by
  default.
- On Windows, paths that differ only by trailing dots or spaces can bypass
  exact-name protected-path matching.
- The chat transport does not yet enforce a localhost-only guard; a configured
  remote `ollama.host` can receive prompts.
- The local master key is still stored beside the encrypted data, so current
  encryption is casual-inspection protection, not OS-backed key custody.
- Local traces and logs are durable evidence artifacts, but they are not
  tamper-evident.

## Wave 6 Map

### Wave 6 trust track

These open trust tickets are folded into the Wave 6 trust track and must be
re-scoped against current code by later tickets:

T274, T276, T281, T283, T286, T301, T319.

### Capability backlog, explicitly deferred

These open capability tickets are parked as v1/deferred work and are not the
Talos identity for Wave 6:

T294, T296, T299, T300, T302, T303, T304, T627.

This includes OCR, RAG/extraction breadth, PowerPoint, static-web browser
loading, and related capability expansion. They remain backlog, not current
trust-surface readiness.

## Site copy recommendations

Do not edit `site/` in T833. The site is owner-managed active work. The
recommendations below are for the owner to apply after this docs/test pass.

- `site/index.html:349` currently says: "Protected paths require explicit
  approval." Recommended bounded wording: "Protected paths require explicit
  approval under current classifier limits; on Windows, trailing-dot or
  trailing-space path variants can bypass exact-name protected-path matching."
- `site/index.html:356` currently says: "Workspace escape and protected
  mutation fail closed." Recommended bounded wording: "Workspace escape and
  protected mutation are denied by policy; protected-path matching still has
  the documented Windows trailing-dot/trailing-space caveat."
- `site/index.html:402` currently says: "keeps every turn on your machine."
  Recommended bounded wording: "keeps turns on your machine when the chat
  endpoint is local; a configured remote `ollama.host` or
  `engines.llama_cpp.host` receives prompts."
- `site/src/main.js:582` repeats the "keeps every turn on your machine" copy.
  Apply the same local-endpoint caveat.
- `site/src/main.js:605` says: "Private documents already stay on your machine
  today." Recommended bounded wording: "Private-document workflows remain v1;
  default developer mode can send approved protected reads and extracted
  document text to model context."

## File Classification

| File | Classification |
| --- | --- |
| `README.md` | doc |
| `AGENTS.md` | policy doc |
| `docs/user/local-privacy-and-artifacts.md` | doc |
| `docs/user/model-setup.md` | doc |
| `docs/user/commands.md` | doc |
| `docs/user/workspaces-and-indexing.md` | doc |
| `docs/user/index.md` | doc |
| `docs/setup-managed-models.md` | doc |
| `docs/architecture/01-execution-discipline-and-local-trust.md` | architecture doc |
| `docs/architecture/03-local-turn-trace-model-v1.md` | architecture doc |
| `docs/architecture/04-declarative-allow-ask-deny-permissions.md` | architecture doc |
| `docs/architecture/10-command-execution-architecture-design.md` | architecture doc |
| `src/test/java/dev/talos/docs/TrustClaimsHonestyTest.java` | honesty test |
| `work-cycle-docs/tickets/open/[T833-open-high] wave6-trust-surface-honest-disclosure.md` | ticket |
| `work-cycle-docs/reports/t833-wave6-trust-surface-honest-disclosure.md` | report |
| `work-cycle-docs/wiki/CURRENT-STATE.md` | wiki |
| `work-cycle-docs/wiki/INDEX.md` | wiki |
| `work-cycle-docs/wiki/LOG.md` | wiki |

## Explicit Non-Authorization

T833 does not authorize:

- the five HIGH code fixes from the audit;
- production `src/main` changes;
- candidate cut, release notes, or release metadata;
- Qodana policy changes;
- compaction Phase 2;
- capability work;
- `site/` edits.

## Verification

- focused honesty test: PASS,
  `.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon`.
- full `check`: PASS,
  `.\gradlew.bat check --no-daemon`, build successful in 2m 1s.
- `wikiEvidenceCloseGate --rerun-tasks`: PASS,
  `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon`, 10/10
  actionable tasks executed.
- `git diff --check -- . ':!site'`: PASS.
- `git status --short -- . ':!site'`: scoped docs/wiki/test/report/ticket
  changes only.
- production `src/main` change: none.
- `site/` touch: none for T833; pre-existing owner `site/` work remains
  unstaged and out of scope.
