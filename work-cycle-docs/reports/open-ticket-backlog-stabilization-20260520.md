# Open-Ticket Backlog Stabilization - 2026-05-20

Branch: `v0.9.0-beta-dev`
Commit reviewed: `ae07ef6daf46602b06eff51623e47b314c2b6949`
Candidate version: `0.9.9`
Mode: no version bump; no candidate packet

## Purpose

This report reconciles the current open-ticket backlog after the private-document
approval/provenance work, source-derived verification work, Python command-boundary
work, static-web convergence work, and capability-doc updates.

The conclusion is not that Talos is beta-ready. The conclusion is narrower:
several former implementation blockers are now closed or reduced, while the
remaining open list is mostly release-evidence, broad audit, or deferred
capability work.

## Tickets Closed In This Stabilization Wave

- `T269`: user-facing beta file capability matrix and warning.
- `T277`: CI-grade generated-artifact canary scan wired into `check`.
- `T307`: beta-relevant semantic verification slices.
- `T320`: PDF/Office extraction versus generation claim split.
- `T322`: exact three-file static web convergence.
- `T323`: office document multisource report verification.
- `T325`: Python command-boundary and audit assertions.
- `T332`: static-web selector fix must not expose rename path.

These tickets were moved to `work-cycle-docs/tickets/done/` only after code,
tests, or live/audit evidence existed in the working tree.

## Remaining Open Tickets By State

### Still Open: Release Evidence Or Process Gates

- `T274`: source-crosscheck and release-gate discipline.
- `T276`: runtime log and tool-parameter redaction.
- `T280`: two-model live audit before beta.
- `T283`: broad log redaction audit.
- `T284`: live two-model audit execution results.
- `T301`: document capability docs and release-claim drift prevention.
- `T306`: synchronized approval live audit runner.
- `T312`: full prompt-bank native-tool coverage.
- `T313`: TalosBench piped approval drift on missing approval prompt.
- `T319`: blended manual audit scenario bank.

These are not mostly feature tickets. They are evidence, release discipline, or
audit integrity tickets. Closing them requires fresh current-head evidence, not
more prose.

### Implemented, Awaiting Broader Evidence

- `T281`: private-mode UX exists; broader sensitive-folder user-facing proof
  remains open.
- `T286`: backend setup and smoke work; full prompt bank still needs execution.
- `T296`: private-document RAG policy enforcement exists; richer document
  chunk/citation provenance and live artifact evidence remain open.
- `T303`: core file-capability state machine exists; dynamic encrypted/corrupt
  and limit-outcome expansion remains open.

These should not be treated as immediate architecture-refactor blockers. They
need focused follow-up only if their remaining evidence is required for the next
candidate claim.

### Deferred Beyond Current Beta Or Conditional

- `T294`: local image/OCR extraction remains v1 scope, not current beta scope.
- `T302`: PowerPoint extraction remains intentionally unsupported for beta.
- `T304`: extraction cache remains deferred unless performance evidence proves
  direct extraction too slow.

These tickets remain in `open/` because the repository has no separate
`deferred/` ticket directory. Their status headers explicitly prevent them from
being read as current beta implementation blockers.

### Performance And Corpus Quality

- `T299`: document extraction fixture corpus and live audit remains open for
  larger/adversarial fixture quality.
- `T300`: beta-core extraction limits exist, but realistic Windows
  performance/resource benchmarks remain open.

These are quality gates. They matter before stronger document-product claims,
but they are not the same as the already-closed private-document provenance
approval gate.

## Current Next Implementation Blocker

The next implementation target should not be a broad architecture cleanup.

Best next blocker: `T276/T283` log and runtime-artifact redaction audit, narrowed
to current high-risk call sites.

Reason:

- The private-document and approval paths now have stronger tests/live evidence.
- The remaining biggest trust risk is not "can Talos classify a task"; it is
  whether provider, retry, command, session, and CLI diagnostics can still
  persist raw sensitive values through unreviewed logging paths.
- This can be attacked with source scanning, deterministic log-capture tests,
  and targeted artifact scans without destabilizing task classification or
  verifier code.

Second-best next blocker: `T300` performance/resource benchmarks for PDF/DOCX/XLSX
on Windows.

Reason:

- This is needed before strong document-extraction product claims.
- It should not start until the dirty stabilization change set is verified and
  committed, because benchmark evidence is easy to contaminate with stale local
  artifacts.

## Current Beta Strengths

- Private-document provenance now has runtime metadata, model-handoff gating,
  RAG indexing policy enforcement, and per-turn approval evidence.
- Static web creation/repair has materially stronger target preservation and
  selector verification.
- Source-derived summaries now have per-source verification pressure instead of
  aggregate-overlap false confidence.
- Python execution is honest: Talos can create source files, but unsupported
  execution/test requests do not get falsely reported as run.
- Capability docs now explicitly separate extraction from binary document
  generation.

## Current Beta Problems

- Full two-model prompt-bank evidence for current head is still open.
- True PTY/JLine evidence remains manual, not automated.
- Broad runtime log redaction audit is still incomplete.
- Document extraction is still limited to text extraction; larger/adversarial
  PDF/DOCX/XLS/XLSX fixture evidence is not enough for broad office-worker claims.
- Image/OCR and PowerPoint must remain out of beta claims.
- The current working tree is broad and must be stabilized before starting a new
  implementation batch.

## Verification Status At Time Of Report

Already passed in this stabilization wave:

```powershell
.\gradlew.bat test --tests "dev.talos.docs.ReadmePrivacyCopyTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NativeToolSpecPolicyTest.scopedTargetLimiterContractInApplyExcludesWorkspaceOrganizationNativeSpecs" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
git diff --check
```

Required before committing this wave:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat e2eTest --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=work-cycle-docs/reports,work-cycle-docs/tickets" --no-daemon
git diff --check
```
