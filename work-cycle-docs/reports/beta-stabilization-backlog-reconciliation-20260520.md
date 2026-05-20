# Beta Stabilization Backlog Reconciliation - 2026-05-20

## Environment

```text
Branch: v0.9.0-beta-dev
Start commit: 8d3a053a
Candidate version: 0.9.9
Version bump: no
Scope: ticket/report stabilization only
```

## Decision

T295 is closed with deterministic, live-model, and true Windows ConPTY/JLine private-document approval evidence. The next useful phase is backlog stabilization before another broad audit or feature slice.

The backlog was reconciled into these states:

- `done`: acceptance criteria are satisfied by current deterministic/live evidence.
- `implemented-awaiting-evidence`: implementation exists, but broader prompt-bank/candidate/live evidence is still missing.
- `still-open`: a concrete blocker remains.
- `deferred-beyond-beta`: intentionally outside the current beta scope.

No patch-version bump or changelog update was performed in this pass.

## Verification Gate

All commands below passed on `v0.9.0-beta-dev` at start commit `8d3a053a` with `talosVersion=0.9.9`:

```text
.\gradlew.bat check --no-daemon
.\gradlew.bat e2eTest --no-daemon
.\gradlew.bat validateSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=local/manual-testing/t295-pty-conpty-20260520-r1/artifacts" "-PptyManualWorkspace=local/manual-workspaces/t295-pty-conpty-20260520-r1/workspace" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
npm test --prefix site
npm run build --prefix site
npm run test:e2e --prefix site
git diff --check
```

`git diff --check` emitted only CRLF normalization warnings for existing Markdown files and exited successfully.

## Tickets Moved To Done

Closed as implemented or superseded by stronger current tickets/evidence:

```text
T270 rag-index protected and unsupported format safety
T271 prompt-debug/trace/session redaction release gate
T272 private-folder mode V1 design and implementation
T273 local document extraction roadmap
T278 RAG index policy versioning and dirty-index invalidation
T282 config default/fallback privacy parity
T285 artifact scanner surface coverage
T287 sensitive workspace detector tokenization
T288 runtime artifact scan release task
T289 private-mode scripted e2e scenarios
T297 static-web edit reliability before beta
T298 private-mode reindex policy gate
T308 live static-web mutation convergence
T309 pending expected-target remembered-approval boundary
T310 static-web selector replacement preservation verifier
T311 append-line full-write preapproval preservation
T314 CLI semantic UI terminal audit
T315 follow-up site creation classification
T316 static-site artifact completeness false-success blocking
T317 no-progress failure-policy outcome context
T318 correction prompt apply-mode inheritance
T321 general QA no-workspace boundary
T324 source-to-code target extraction
```

Important closure notes:

- `T321` is closed by `T327`.
- `T324` is closed by `T328`.
- `T308` is closed by the later `T331` GPT-OSS live-bank pass.
- `T316` closes the verifier false-success problem; full exact three-file static-site convergence remains `T322`.

## Remaining Open Backlog

Current implementation blockers:

```text
T307 mutation semantic verification beyond exact edits
T322 exact three-file static web convergence
T323 office document multi-source report verification
T325 Python command boundary and audit assertions
```

Current evidence/candidate/audit blockers:

```text
T280 full two-model live audit before beta
T284 full two-model audit execution results
T286 two-model backend setup and full prompt-bank execution
T306 synchronized approval runner full prompt-bank expansion
T312 full prompt-bank native-tool coverage evidence
T313 synchronized approval-sensitive full prompt-bank path
T319 blended manual audit scenario automation/live expansion
```

Current release-copy/process blockers:

```text
T269 user-facing capability matrix and beta warning
T274 source-crosscheck and release-gate discipline
T301 document capability docs and release-claim drift prevention
T320 PDF/Office extraction versus binary generation claim split
```

Current privacy/logging/document hardening blockers:

```text
T276 broader runtime log redaction audit
T277 CI/check integration decision for artifact canary scanning
T281 broader private-mode user-facing proof
T283 broad runtime log redaction audit
T296 richer extraction chunk/citation provenance for RAG
T299 larger maintained private-document fixture corpus
T300 realistic extraction performance/resource benchmarks
T303 dynamic extraction outcome expansion
```

Deferred beyond beta:

```text
T294 local image/OCR extraction
T302 PowerPoint extraction
T304 extraction cache unless performance evidence requires it
```

## Next Best Implementation Move

The next implementation blocker is `T307`.

Reason: the private-document release gate is closed, the narrow live approval blockers are closed, and the remaining user-facing coding failures converge on semantic verification rather than another privacy-core patch. `T307` is broader than a single static-web scenario: it owns false-success prevention for semantic rewrites where exact old/new literal replacement is not enough.

Recommended next slice:

```text
Plan and implement a narrow semantic-verification increment under T307,
starting with the smallest failing example not already covered by exact
replacement, append-line, or static selector verification.
```

Do not start another five-scenario audit until:

- the reconciled backlog is committed,
- the stabilization verification gate passes,
- and the next implementation blocker has a focused test plan.
