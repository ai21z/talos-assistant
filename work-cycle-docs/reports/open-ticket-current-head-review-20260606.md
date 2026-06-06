# Open Ticket Current-Head Review - 2026-06-06

Branch: `v0.9.0-beta-dev`
Commit reviewed: `739e9dd8ce68`
Candidate version: `talosVersion=0.9.9`
Mode: ticket/code review only; no release candidate packet

## Scope

This report reviews every file currently in `work-cycle-docs/tickets/open/`
against the current source tree. The goal is backlog hygiene, not release
certification.

Open-ticket lifecycle rule inspected:

- `work-cycle-docs/tickets/README.md` says completed tickets should be renamed
  and moved to `done/`.
- `work-cycle-docs/tickets/open/README.md` says `deferred-beyond-beta` tickets
  may remain in `open/` until the project adds a deferred directory.

## Source Evidence Checked

Representative current-code evidence:

- Redaction/sink safety: `dev.talos.safety.SafeLogFormatter`,
  `ProtectedContentSanitizer`, `ProtectedContentPolicy`,
  `SensitiveLogRedactionTest`, `RuntimeSinkSafetyInventoryTest`,
  provider-body hash/length diagnostics in `EngineException`, and
  malformed-response trace tests.
- Document extraction: `FileCapabilityPolicy`, `DocumentExtractionService`,
  `DocumentExtractionPreflight`, `DocumentExtractionOutcomeVerifier`,
  `DocumentExtractionCanonicalFixturesTest`, `FileCapabilityPolicyV3Test`,
  `ReadmePrivacyCopyTest`.
- Audit runner/evidence lanes: `SynchronizedApprovalAuditRunner`,
  `SynchronizedCliProcessDriver`, Gradle `runSynchronizedApprovalAudit`,
  `tools/manual-eval/run-talosbench.ps1`, `FullAuditCoverageDocumentationTest`,
  TalosBench `SYNC_REQUIRED` behavior.
- Static web browser behavior: `StaticWebBrowserBehaviorVerifier` still contains
  the inline workspace-JS fallback and `FallbackClickObservation`; T626 tests
  cover causality, but T627's root-cause decision is not closed.
- Static-web post-T690 work: current source includes durable static-web
  requirements, forbidden artifacts, Tailwind/local-artifact guards, remote
  asset verification, compact repair evidence, and blank required-asset guards.
  The current open-ticket registry does not contain T661-T693/T694/T695/T696
  ticket files.

## Classification

| Ticket | Current classification | Decision | Evidence basis |
|---|---|---|---|
| `T274` source-crosscheck/release discipline | still open process gate | keep open | Related reports exist, but the ticket is explicitly about release discipline and future gate enforcement, not a completed runtime feature. |
| `T276` runtime log/tool parameter redaction | implemented subset, evidence delegated to T283 | keep open for now; possible later merge into T283 | Safe formatting and deterministic tests exist, but the ticket itself states broader runtime log audit remains under T283. Closing it separately would hide the remaining broad-evidence dependency. |
| `T280` two-model live audit before beta | release evidence gate | keep open | Lane-labeled evidence exists historically, but no clean current-head/versioned candidate full prompt-bank packet exists for `739e9dd8ce68`. |
| `T281` private-mode UX/sensitive-folder warning | implemented UX, broader proof open | keep open | `/privacy` and sensitive-folder behavior exist with tests, but private-paperwork positioning remains blocked by broader live/private evidence. |
| `T283` broad log redaction audit | still open audit gate | keep open | Sink-safety code and focused installed-product evidence exist; broad two-model prompt-bank log/artifact evidence remains explicitly listed as the blocker. |
| `T284` live two-model audit execution results | release result artifact gate | keep open | Overlaps T280 but is the results/report side of the gate. Do not merge until a current-head full audit packet exists. |
| `T286` two-model local backend setup | setup/smoke implemented, full prompt bank open | keep open | Backend smoke/preflight is implemented, but the ticket acceptance still includes both models completing the prompt bank. |
| `T294` local image/OCR extraction | deferred beyond beta | keep open as future/v1 | Code has experimental OCR plumbing and disabled-by-default policy. README and AGENTS freeze image/OCR out of beta claims; not obsolete. |
| `T296` extraction RAG integration | private RAG gate implemented; provenance incomplete | keep open | `RagService`/`Indexer` enforce private RAG policy and metadata, but richer page/sheet/cell chunk provenance remains open. |
| `T299` extraction fixtures/BDD/live audit | partial corpus evidence | keep open | Canonical fixtures and live generated fixtures exist; larger maintained/adversarial corpus remains missing. |
| `T300` extraction dependency/perf/resource limits | partial implementation | keep open | Extraction caps/preflight exist; realistic Windows performance/resource benchmarks remain unrun. |
| `T301` document docs/release claims | docs matrix implemented, drift prevention open | keep open | README capability matrix and docs tests exist, but release-report drift prevention is a continuing release gate. |
| `T302` PowerPoint deferred | no beta implementation needed | keep open as deferred | `FileCapabilityPolicy` keeps PPT/PPTX deferred/unsupported and tests guard no fabrication. Not a current beta blocker. |
| `T303` file capability policy V3 | core implemented; dynamic outcomes incomplete | keep open | `FileCapabilityPolicyV3Test` and extraction status enums exist, but richer encrypted/password/corrupt/limit outcome propagation remains incomplete. |
| `T304` extraction cache/invalidation | deferred conditional | keep open as deferred | No extraction cache exists by design; ticket should activate only if performance evidence shows direct extraction too slow. |
| `T306` synchronized approval runner | runner implemented; broader integration open | keep open | Java runner, process driver, Gradle tasks, artifact bundles, and tests exist. Full prompt-bank integration and true PTY lane separation remain active evidence concerns. |
| `T312` full prompt-bank native tool coverage | coverage implemented; candidate evidence open | keep open | Native-tool coverage guard and TalosBench coverage exist. Current-head release-grade lane evidence still belongs to the broader audit gate. |
| `T313` piped approval drift | fail-closed guard implemented; synchronized path open | keep open for now; merge candidate later | `run-talosbench.ps1` has `SYNC_REQUIRED` and drift detection. Do not close until the synchronized full prompt-bank path is reconciled with T306/T312/T280. |
| `T319` blended manual audit scenario bank | first bank exists, expansion open | keep open | Scenario bank exists, but automation/live-model expansion is explicitly unfinished. |
| `T627` static-web browser natural loading decision | not implemented | keep open | HtmlUnit fallback still exists in `StaticWebBrowserBehaviorVerifier`; T626 made it causally honest, not removable. |

## Merge/Delete Decisions

No ticket should be deleted now.

No ticket should be moved to `done/` in this pass.

Potential future merges, not safe immediate actions:

- `T276` into `T283`: only after broad log/artifact evidence is complete, because
  T276 currently documents the implemented redaction slice and T283 owns the
  remaining broad audit.
- `T284` into `T280`: only after a current-head full two-model audit packet
  exists, because T280 is the gate/runbook and T284 is the result artifact.
- `T313` into `T306`/`T312`: only after the synchronized full prompt-bank route
  is either implemented or explicitly split from TalosBench. The fail-closed
  piped-runner behavior is implemented, but the release-evidence path is not
  fully reconciled.

## Missing Ticket Registry Coverage

The current open-ticket directory does not contain files for the recent
static-web work batch T661-T693 or the planned post-audit follow-ups. This is a
bookkeeping gap, not a code failure.

High-confidence new/open ticket candidates after the latest Qwen-only T694-style
manual audit:

- Durable static-web requirements/exact-target persistence across dirty
  continuation/session boundaries.
- General external static asset/framework coherence, not Tailwind-only:
  runtime/build/CDN distinction for any user-requested frontend framework or
  external static asset path.

Do not create or close those in this review report unless the project wants the
conversation-only T69x plans formalized into `work-cycle-docs/tickets/open/`.

## Bottom Line

The old open backlog is mostly valid. It is not a pile of stale implementation
tickets; it is a mix of release-evidence gates, implemented-but-awaiting-broader
evidence records, and intentionally deferred future capabilities.

The only real hygiene problem found is that recent static-web reliability work
is not represented as ticket files in the current open/done registry. The next
backlog action should be to formalize the next static-web follow-up tickets, not
to delete old document/privacy/audit gates.
