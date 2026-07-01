# Current Architecture Risk Report

Branch: `feature/archunit-architecture-guards`
HEAD at analysis: `ff032e5e`
Candidate version (`gradle.properties`): `talosVersion=0.9.9`
Status: engineering evidence, not marketing

## Evidence base

- `.github/assistant-instructions.md` (layering + key packages)
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/11-architecture-guardrails.md`
- `README.md` / `AGENTS.md` (product doctrine, beta scope)
- ArchUnit hard guards: `dev.talos.architecture.LayeredArchitectureTest` (11 rules, all passing)
- `build/reports/talos/architecture/architecture-discovery-report.md`
- `build/reports/talos/architecture/architecture-cycle-report.md`
- `build/reports/talos/architecture/harness-spine-access-report.md`
- `git` branch/version state

All quantitative claims below are copied from those reports. Nothing here is invented.
Counts collapse inner classes into their top-level class and only count `dev.talos -> dev.talos` edges.

---

## 1. Executive verdict

**Coherent?** Yes, at the layer-boundary level. The documented 8-layer model
(safety → spi → core/engine/tools → runtime → cli, with `app` as composition
root and `api` as seam) is real and enforced. `safety` and `spi` have **zero**
outgoing `dev.talos` edges - the lowest trust layers are genuinely isolated, not
aspirationally isolated. All 11 ArchUnit guards pass.

**Improving?** Yes. This branch added bytecode-level guards plus three report-only
discovery passes, and the regex ratchet baseline is clean/empty. The architecture
is now measured, not assumed.

**Fragile?** Internally, in one place: `dev.talos.runtime`. It is 257 top-level
classes (vs cli 103, core 90) and forms a single 16-subpackage strongly-connected
component. The layer *walls* are solid; the *runtime interior* is a tangle.

**Beta-release risky?** Not from a layer-boundary standpoint - external boundaries
hold and there is no protected-content/approval leak in scope here. The real risk
is **maintainability tax**, not correctness: the runtime SCC and the
`AssistantTurnExecutor` hub make change expensive and raise regression odds. This
is acceptable for a beta but should not be allowed to grow.

Bottom line: **structurally sound shell, congested core. Safe to keep evolving;
not safe to ignore the runtime tangle.**

---

## 2. Architecture strengths (evaluated, not assumed)

- **Local-first identity** - Doctrine in AGENTS.md/README is consistently
  reflected in package names and layering (no cloud/daemon packages). Credible.
- **Layer isolation of trust-critical code** - `safety` (5 classes, 0 out-edges)
  and `spi` (27 classes, 0 out-edges) depend on nothing upward. This is the single
  strongest architecture fact in the codebase.
- **Execution-harness spine exists and is named** - `AssistantTurnExecutor` →
  `ToolCallLoop` → tool-call stages → verification → outcome is a real, traceable
  flow, not folklore. `ToolCallLoop` fan-in 45 confirms it is the genuine hub.
- **Current-turn planning** - `CurrentTurnPlan` (fan-in 18, fan-out 9) is a
  well-shaped per-turn aggregate: widely consumed, thin outward. Healthy.
- **Tool-surface policy** - `ToolSurfacePlanner` (fan-out 12, fan-in 2) is
  contained and single-purpose. Good.
- **Evidence obligations / verification** - `EvidenceObligationPolicy` (8/6),
  `EvidenceObligationVerifier` (5/5), `StaticTaskVerifier` (20/8) are present and
  reasonably bounded except `StaticTaskVerifier`'s breadth (see risks).
- **Traces** - `LocalTurnTraceCapture` exists and is heavily wired (fan-out 31,
  fan-in 21), consistent with the trace-as-evidence doctrine.
- **Context handling** - `ConversationManager` (fan-out 5, fan-in 9) is small and
  contained.
- **Work-test cycle / governance** - AGENTS.md + assistant-instructions define
  inner/candidate loops and quality-tooling isolation; this branch followed it
  (ArchUnit isolated, not auto-merged).

---

## 3. Architecture risks (evidence-backed)

| Risk | Evidence | Severity |
|------|----------|:--------:|
| **`AssistantTurnExecutor` god-object** | fan-out 63, very heavy outgoing calls (146 calls into `repl.Context` alone); AGENTS.md explicitly warns it must be "an orchestrator, not a warehouse" | High |
| **`runtime` mega-SCC** | cycle report: all 16 runtime subpackages in one SCC; 257 classes | High |
| **Runtime control-spine knots** | `policy↔toolcall`, `toolcall↔verification`, `task↔verification` mutual cycles | High |
| **`ExecutionOutcome` is not a value object** | fan-out 30, fan-in 2 - a "result" type reaching into 30 classes incl. answer guards/renderers | Medium |
| **`StaticTaskVerifier` breadth** | fan-out 20 across capability/task/expectation/repair/toolcall - verifier knows about a lot | Medium |
| **`core ↔ tools` cycle** | `core→tools` 8 edges (the leak), `tools→core` 38 (allowed) | Medium |
| **CLI composition cycle** | `cli.modes ↔ cli.prompt ↔ cli.repl` mutual cycle | Medium |
| **`LocalTurnTraceCapture` bidirectional coupling** | fan-out 31 / fan-in 21, mutual edges with policy/task/verification/outcome | Medium (privacy/audit surface) |
| **Branch/version drift** | default branch `origin/main`; active dev `v0.9.0-beta-dev`; but `talosVersion=0.9.9` (top released changelog `[0.9.9] 2026-05-15`). The branch name implies 0.9.0; the version is 0.9.9 | Low (release hygiene) |
| **Two enforcement mechanisms can drift** | gen-2 ArchUnit guards have **no** `build.gradle.kts` regex counterpart | Low |

Note on the trace coupling: it is the one Medium risk with a *trust* dimension,
not just maintainability - trace capture touching policy/verification two-way is
worth a redaction/ownership review (ref `docs/architecture/03`).

---

## 4. Layer-boundary status

**Hard guards (11, all passing) - `LayeredArchitectureTest`:**

Generation 1 (mirror the `build.gradle.kts` regex ratchet):
`runtime/core ↛ cli`; `core ↛ runtime`; `tools ↛ runtime`; `engine ↛ runtime`;
`safety ↛ all-talos-layers`; `spi ↛ cli/core/runtime/tools`.

Generation 2 (this branch, promoted only after 0-edge confirmation):
`runtime.policy ↛ cli`; `runtime.verification ↛ cli`;
`runtime.toolcall ↛ cli.repl`; `tools ↛ cli`; `spi ↛ app`.

**Report-only (non-zero today - NOT guarded):** `core↔tools` cycle, runtime
mega-SCC, the three control-spine knots, the CLI composition cycle, and the
hub-size hotspots. All documented in `docs/architecture/11`.

**Accepted exceptions:** `api` and `app` unconstrained by design; `tools→core`
(38 edges) is an allowed direction.

**Package dependency map (out-edges):** `cli` is the heaviest consumer (→runtime
278, →core 167); `runtime` →tools 151 (legit invocation), →spi 76, →core 64;
`safety`/`spi` = 0 out. Direction is correct everywhere except the 8 `core→tools`
back-edges.

---

## 5. Top 10 refactor candidates

| # | Target | Why it matters | Risk if left | Ticket direction | Priority |
|---|--------|----------------|--------------|------------------|:--------:|
| 1 | `cli.modes.AssistantTurnExecutor` | Spine apex; fan-out 63, warned against in AGENTS.md | Change-expensive, regression-prone orchestration warehouse | Extract policy marshalling / retry / final-answer patching into collaborators; target materially lower fan-out | P1 |
| 2 | `dev.talos.runtime` mega-SCC | 16 subpackages in one SCC blocks any clean extraction | Runtime ossifies; refactors stall | Define one-way seams; start by breaking `policy↔toolcall` | P1 |
| 3 | `core → tools` (8 back-edges) | Only top-level cycle; most tractable | Blocks promoting `core ↛ tools` to a hard guard | Move shared types so deps flow tools→core only; then guard | P1 |
| 4 | `runtime.toolcall ↔ runtime.verification` | Verifier/loop entanglement undermines false-success prevention | Verification logic hard to reason about/trust | Introduce a verification contract the loop depends on one-way | P2 |
| 5 | `cli.modes.ExecutionOutcome` | "Result" type with fan-out 30 | Hidden logic hub masquerading as a value object | Confirm/extract to thin result; push rendering/decision out | P2 |
| 6 | `runtime.verification.StaticTaskVerifier` | fan-out 20; verifier knows too much | Brittle verification; coupling to repair/toolcall | Split per-capability verifiers behind a registry | P2 |
| 7 | `cli.modes ↔ cli.prompt ↔ cli.repl` cycle | CLI composition tangle | Adapter layer hard to restructure | Define one-way CLI composition seam (`prompt ↛ modes`) | P2 |
| 8 | `runtime.trace.LocalTurnTraceCapture` | fan-out 31 / fan-in 21, two-way with policy/verification | Audit/redaction surface; coupling | Make trace a sink that depends on others one-way; review redaction ownership | P2 |
| 9 | `runtime.policy` spread | Policy markers scattered (AGENTS.md "policy ownership") | Policy logic hard to locate/own | Consolidate per `docs/architecture/02` ownership map | P3 |
| 10 | Enforcement drift (ArchUnit vs regex ratchet) | gen-2 guards not mirrored in `build.gradle.kts` | Silent divergence between the two mechanisms | Approval-gated: add matching regex entries OR document ArchUnit as authoritative | P3 |

---

## 6. What NOT to refactor yet

- **`safety` and `spi`** - already ideal (0 out-edges). Any churn is pure risk
  with no architectural upside.
- **High fan-in shared types** (`TaskContract` 66, `ToolCall` 66, `ChatMessage`
  60, `Config` 59) - high fan-in on contracts/records is correct, not a defect.
  Do not "fix" these.
- **`api` / `app`** - intentionally unconstrained seam/composition root. Leave
  unguarded.
- **`tools → core` (38 edges)** - an allowed, healthy direction. Do not invert.
- **The runtime SCC in one pass** - do NOT attempt a big-bang untangle. AGENTS.md:
  prove parity before deleting legacy; smallest coherent change. Break it edge by
  edge behind tests.
- **`CurrentTurnPlan` / `TaskContractResolver`** - high fan-in but thin fan-out;
  healthy aggregates. Keep thin; don't restructure.

---

## 7. Scorecard

Scores are /10, honest, with rationale. Uncertainty stated where present.

| Dimension | Score | Rationale |
|-----------|:-----:|-----------|
| Architecture coherence | **7/10** | Layer model real and enforced; let down by the runtime interior SCC. |
| Local-trust design | **8/10** | `safety`/`spi` isolation is excellent; minor concern is two-way trace↔policy/verification coupling. **Uncertain** beyond statics: runtime behavior (approval/protected reads) not exercised here - this score is structure-only. |
| Testability | **6/10** | Architecture now self-testing (ArchUnit + reports); but the runtime SCC and god-object hub make unit isolation hard. **Uncertain**: did not run the full suite, only the architecture tests. |
| Maintainability | **5/10** | The clearest weakness: 257-class runtime SCC + fan-out-63 orchestrator = high change cost. |
| Release readiness (architecture) | **7/10** | Boundaries hold; no boundary-level blocker. Internal debt is a tax, not a blocker. Branch/version drift is a hygiene ding. **Uncertain**: release readiness in the product sense depends on live audits not run here. |
| Top-tier comparison readiness (vs local coding assistant / Codex / gemini-cli) | **5/10** | Discipline doctrine is competitive; execution-harness modularity is behind - the spine is monolithic where top-tier tools are decomposed. |

---

## 8. Next 5 tickets (proposed, not implemented)

1. **[arch] Cut `core → tools` back-edges and promote `core ↛ tools` to a hard
   guard.** 8 edges; smallest high-value win; unlocks a new ratchet entry.
2. **[arch] Break `runtime.policy ↔ runtime.toolcall` with a one-way contract.**
   First incision into the runtime SCC; pick the thinnest shared seam.
3. **[arch] Decompose `AssistantTurnExecutor`.** Extract retry/marshalling/
   final-answer responsibilities into named collaborators; assert reduced fan-out
   (could later become a soft fan-out report check).
4. **[arch] Reclassify `ExecutionOutcome`.** Confirm it should be a thin result
   type; move renderer/guard wiring out; re-measure fan-out.
5. **[hygiene] Resolve branch/version drift.** Reconcile `v0.9.0-beta-dev` branch
   name vs `talosVersion=0.9.9`, and document whether `main` or `v0.9.0-beta-dev`
   is the intended default; record the decision in the release runbook.

---

## How to run the architecture tests

```powershell
.\gradlew.bat test --tests "dev.talos.architecture.*" --no-daemon
```

Result at this analysis: **BUILD SUCCESSFUL** (all architecture tests pass,
including the 11 hard guards and the 3 report-only discovery passes).
