# Beta Candidate Readiness - v0.9.0-beta-dev @ `261ca90d`

- Date: 2026-06-24
- Branch / HEAD: `v0.9.0-beta-dev` / `261ca90d`
- Version: `0.10.5` (gradle.properties) → next patch cut = `0.10.6`
- Scope: read-only scoping of what must be true before cutting a beta candidate and before a public push. Evidence gathered by a 6-area fan-out; synthesized + spot-verified by Opus. No code changed.

## Verdict

The candidate is **mechanically ready to cut**: tree clean, the cut is a single hermetic scripted command, the local gate (`check`) is portably green (T865), and doctrine's candidate loop is fully local - the remote Linux Actions green is a public-push gate, not a candidate gate.

Two things stand between "now" and a defensible beta candidate:

1. **CHANGELOG hygiene (mechanical, code-adjacent).** Four behavior-changing tickets shipped since 0.10.5 - **T855, T856, T858, T859** - have no `[Unreleased]` entry (verified absent). `bump-patch.ps1` promotes `[Unreleased]` verbatim into the dated entry, so the gap would ship incomplete release notes.
2. **The T842 pre-beta E2E audit (owner-interactive, LARGE).** The one substantive decision: must it run before the candidate cut, or before the public beta? See "The one real decision" below.

Everything else - identifier reconciliation, winget-live, hybrid/vector validation, the T862 + T301 release-doc pass - is **before-public-push**, not before the candidate.

## The seven questions, answered

### 1. Which open tickets are beta-blocking vs deferred?
- 18 open tickets. **Exactly one is a candidate-relevant gate: T842** (owner-run pre-beta full E2E audit; last full audit ~2026-05-13, predates the Wave 5 + Wave 6 arc this candidate ships).
- **T862** (Maven workspace-profile docs): docs/DX, self-classified "candidate follow-up", no runtime diff. The beta makes **no Maven claim**, so its absence falsifies nothing. NOT a blocker; foldable into a release-doc pass.
- **T863 / T294 / T302 / T304**: `deferred-beyond-beta`. Stay open, do not block (provided launch copy avoids tamper-evident/crypto-audit, image/OCR, and PowerPoint claims).
- **Private-document cluster** (T276, T281, T283, T286, T296, T299, T300, T301, T303): carry "release gate: yes" but only for a **private-document beta**, which this packaged developer/text beta explicitly does NOT claim (README freeze, pinned by `TrustClaimsHonestyTest`). Deferred for this candidate; they re-arm as public-push gates only if positioning adds private-document claims.
- **T274 / T319**: release-process discipline / audit methodology that feeds T842. Not independent gates.
- **T301** also flags **stale work-cycle report statements** (reports still say the full prompt bank was not run, while a later two-model run completed) - a doc-consistency cleanup to fold into the release-doc pass with T862.

### 2. Is CHANGELOG `[Unreleased]` coherent for a patch bump?
- Well-formed and factually accurate for its 11 listed entries (spot-checked T860/T861/T864 = TRUE vs shipped code). `bump-patch.ps1` will accept it.
- **GAP (before-candidate):** (1) missing **T855, T856, T858, T859** entries - all behavior-changing, all touched `src/main`, all shipped since 0.10.5; (2) a stale in-entry status claim in the **T849** entry ("T849 remains open ... before closeout") while T849 is in `done/` (caught in a cross-check; the rest of `[Unreleased]` was then swept and is clean of stale status language). Both would otherwise ship in the 0.10.6 notes. **Both fixed in this release-prep step** - the four entries were added and the T849 stale note removed. T857/T865 and T843-T846 are legitimately omitted (test-only / docs-only).

### 3. Do docs/site/install copy match the actual beta distribution?
- **Yes.** Windows packaged (jpackage app-image/MSI) and Linux source/developer (`./gradlew` + `install-unix.sh`) copy matches `build.gradle.kts` and is honesty-gated (`PublicInstallPackagingContractTest`, `TrustClaimsHonestyTest`, the site test). No candidate blocker.
- **Before-public-push:** reconcile the identifier namespaces (repo `ai21z` / winget `TalosProject.TalosCLI` / publisher `Vissarion Zounarakis` are never tied together as one project); keep winget/bootstrap "planned-not-live" until a signed GitHub Release exists, flipping the gate tests in the same commit as any copy change.

### 4. Must the literal Linux Actions lane be observed green before candidate or public push?
- **Before public push, not before candidate.** The candidate loop (AGENTS.md 400-464; work-test-cycle.md 204) defines candidate evidence as **local artifacts only** (clean tree, `check`, `e2eTest`, JaCoCo, summaries, packet) and never requires a remote run. The Linux lane runs only on push/PR - structurally a public-push artifact. T861 line 304 splits the gate explicitly: **local-captured** Linux evidence clears the candidate (the Ubuntu 26.04 / Temurin 21 proof - 61 command tests, 0 fail, real posix JVM - satisfies it), and the **remote Actions green** is the later release-evidence step on a deliberate origin push. The lane has no soft-fail, so a remote green will be genuine when observed.

### 5. What model/backend profiles are accepted for beta evidence?
- **Accepted baseline: managed llama.cpp + `qwen2.5-coder-14b` + `gpt-oss-20b`** (doctrine-pinned, `nativeCalling=true`). Three SetupCmd profiles are **experimental, user-selectable, NOT validated beta models**: `qwen36vf-q4km`, `qwen36vf-q6k` (native), `deepseek-v2lite-q4km` (non-native text/tool-prompt path). Ollama is legacy/opt-in. A fresh install runs **no model** until `talos setup models`. Accepted retrieval evidence is **BM25-only**; hybrid/vector retrieval is shipped in config but unvalidated.
- **Before-candidate (wording):** state the baseline explicitly, label the three experimental profiles, and bound all beta wording to the narrow snapshot - one Windows 11 machine, two models, BM25-only. No hardware matrix, vector-quality, low-RAM/GPU, OCR, or PowerPoint claims.

### 6. Is the T842 manual-audit evidence sufficient?
- The **full T842 audit is OPEN / not run** (Completion Evidence = "to be filled by the owner audit run"). Its acceptance (Part A capability bank over both models + artifact-canary scan; Part B owner-interactive trust-surface probes - anti-overclaim, protected-path fail-closed incl. Windows 8.3/trailing-dot, secret redaction across all sinks, localhost-only transport, master-key custody; all 13 native tools probed; `FINDINGS-*.md`) is unmet.
- What **exists and is accepted** is the narrower **T845 snapshot** (2026-06-21): both models, 15 scenarios, 12 PASS / 2 FAIL / 1 MIXED each, **trust surface held in both runs with no trust break**; the three correctness gaps it found were ticketed and closed (T848-T852, all done).
- **Caveat:** T845 (2026-06-21) **predates** the T855-T865 work this candidate ships, including T864 write-layer verification - though those changes were rigorously per-ticket-verified this session (T864 end-to-end + adversarial multi-agent reviews).

### 7. Exact candidate command sequence + packet
- **One hermetic command: `.\scripts\cut-candidate.ps1`** - 8 fail-closed steps: bump 0.10.5→0.10.6; commit "Cut 0.10.6 candidate" (stages only `gradle.properties` + `CHANGELOG.md`); `installDist`; launcher-version cross-check vs gradle.properties; **mandatory post-bump `check --no-daemon`**; `wikiEvidenceCloseGate --rerun-tasks --no-daemon`; `talosQualitySummaries` asserting all four summaries report 0.10.6; write `build/reports/talos/candidate-manifest.json` with a git-derived 40-hex SHA (never hand-typed). Fail-closed on dirty tree / detached HEAD.
- **Dry-run first:** `.\scripts\cut-candidate.ps1 -DryRun` (prints branch, clean state, current→next version, full plan; mutates nothing).
- **Packet** (under `build/reports/talos/`, gitignored): `candidate-manifest.json` + `version/coverage/e2e/qodana-summary.json` + `wiki-lint/current/identity-freshness.json` + `architecture-intelligence/current/` + the jar + JaCoCo. Qodana optional and non-gating (`qodanaNativeFreshLocal --no-daemon`; Docker-Qodana broken on this host).
- All gradle calls already pass `--no-daemon`. Repo is ready to cut now, modulo the CHANGELOG fix.

## The one real decision: T842 before the candidate, or before the public beta?

The two scoped views disagree, and it is genuinely a risk/process call (and it needs the owner - Part B is interactive at a real PTY):

- **Conservative (run T842 first):** T842 is the defined pre-beta audit gate and is "where any new beta-blocking finding would surface." Cutting 0.10.6 before it risks a candidate that T842 then invalidates (→ 0.10.7 churn).
- **Pragmatic (cut now, audit before public beta):** the candidate loop is local; the narrow T845 snapshot held the trust surface on both models; the post-T845 trust changes were per-ticket-verified rigorously. So cut 0.10.6 as a versioned-evidence checkpoint and gate the **public beta** on T842 + remote-green + release-doc.

**Recommendation:** the trust surface has held across T845 + every per-ticket review this session, so the probability of T842 surfacing a *new* trust break is low - but it is non-zero, and it is the ticket's whole purpose. Because T842 needs the owner's interactive session **anyway** and the cut is a single command once it is clean, the lowest-waste path is: **do the mechanical prep now (CHANGELOG + guardrail check), then run T842, then cut.** If you instead want a 0.10.6 checkpoint sooner for internal iteration, cutting now and gating the public beta on T842 is defensible - just treat 0.10.6 as potentially-iterable, not the final beta tag.

## Action plan

### Before candidate
| # | Action | Owner | Effort |
|---|--------|-------|--------|
| 1 | **Done (this step):** added `[Unreleased]` entries for T855/T856/T858/T859 and corrected the stale T849 status note; gates re-run to confirm no honesty gate pins exact CHANGELOG bytes | Opus | SMALL |
| 2 | Confirm honesty guardrail intact: README private-paperwork/image/PPT freeze + `TrustClaimsHonestyTest` green | Opus | TRIVIAL |
| 3 | **Decision:** run T842 now, or accept T845-narrow for the cut and defer full T842 to public-beta | Owner | - |
| 4 | (after 1-3) `.\scripts\cut-candidate.ps1 -DryRun` → `.\scripts\cut-candidate.ps1` → review packet under `build/reports/talos/` | Owner + Opus | MEDIUM |

### Before public push / public beta
- Full **T842** owner E2E audit (Part A + interactive Part B) → `FINDINGS-*.md`, close T842. New beta-blockers become tickets + regressions.
- One observed-green **Linux `linux-command-portability` Actions** run (deliberate origin push) + confirm the Windows `gradle-check` lane green on the same SHA.
- **Identifier reconciliation** (repo / winget ID / publisher) + keep winget "planned-not-live" until a signed release.
- **Hybrid/vector retrieval**: validate via a managed bge-m3 endpoint, or honestly scope the beta to BM25-only.
- **Release-doc pass**: T862 (`ws:maven_verify` recipe + the trust flow in docs, with a deterministic test) + T301 stale-report cleanup. Docs/tests only.

### Deferred (do not block)
- T863, T294, T302, T304 (`deferred-beyond-beta`) - keep open; keep launch copy honest so none re-arm.
- T300 perf benchmarks / T304 extraction cache - opportunistic during the T842 capability run.

## Open questions for the owner
1. T842 timing - are you available for the interactive Part B session before the intended cut? It is the single LARGE item and the cut is instant once it clears.
2. Does the beta ship BM25-only (honest scope-out), or must the managed bge-m3 vector path be validated first?
3. Will launch copy stay strictly inside the honest envelope (no private-paperwork, tamper-evident/crypto-audit, image/OCR, or PowerPoint)? If any are added, the corresponding deferred tickets convert to public-push gates.
4. Is `0.10.6` the intended beta version, or is a deliberate `0.11.0`/`1.0.0` milestone bump wanted (bump-patch only does patch)?
