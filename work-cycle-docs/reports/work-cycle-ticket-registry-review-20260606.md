# Work-Cycle Ticket Registry Review - 2026-06-06

Branch: `v0.9.0-beta-dev`
Commit reviewed: `739e9dd8ce68`
Candidate version: `talosVersion=0.9.9`
Role: ticket manager and static code auditor

## Scope

Reviewed the work-cycle ticket registry under:

- `work-cycle-docs/tickets/open/`
- `work-cycle-docs/tickets/done/`

This was a ticket-track review, not a release certification and not a live
Talos audit.

Project rules checked:

- `AGENTS.md`: inspect before acting, verify before claiming, and use evidence
  rather than final prose.
- `work-cycle-docs/skills/talos-work-cycle/SKILL.md`: reports alone are not
  enough when tickets should be created, updated, moved, merged, or closed.
- `work-cycle-docs/tickets/README.md`: completed tickets should be renamed,
  body status updated, and moved to `done/`.
- `work-cycle-docs/tickets/open/README.md`: deferred tickets may remain in
  `open/` with explicit deferred status.

## Registry Scan

After corrections and new ticket creation:

```text
Total ticket files scanned: 675
Open tickets: 23
Done tickets with normal [Txxx-done-*] prefix: 590
Done legacy/no-prefix files: 62
Duplicate ticket IDs: none
Lifecycle mismatches: none
```

Open tickets now are:

```text
T274, T276, T280, T281, T283, T284, T286, T294, T296, T299,
T300, T301, T302, T303, T304, T306, T312, T313, T319, T627,
T696, T697, T698
```

## Lifecycle Fixes

Three tickets were already under `done/` but their body still said
`Status: open`. I corrected only the body status after verifying source/test
evidence.

| Ticket | Decision | Evidence |
|---|---|---|
| `T124` approved protected read postcondition | body status corrected to `done` | `ProtectedReadAnswerGuard.enforceApprovedProtectedReadPostcondition(...)`, `ExecutionOutcome`, `ProtectedReadAnswerGuardTest`, `ExecutionOutcomeTest`, `AssistantTurnExecutorTest`, trace event `PROTECTED_READ_POSTCONDITION_CHECKED` |
| `T125` prompt-debug protected content redaction | body status corrected to `done` | `PromptDebugRedactor`, `PromptDebugArtifactWriter`, `PromptDebugInspectorProtectedPathParityTest`, `PromptDebugCommandTest`; provider-body JSON is written through redacted rendering |
| `T217` static selector repair write guard | body status corrected to `done` | `StaticSelectorRepairGuard`, `StaticSelectorRepairWriteGuard`, `LoopState.failStaticSelectorRepairAfterInvalidWriteContent(...)`, `StaticSelectorRepairWriteGuardTest` |

No ticket was deleted.

## Open-Ticket Review

The old open backlog remains mostly valid. It is not stale implementation
noise; it is mostly release evidence, privacy/document gates, deferred future
capabilities, and one browser-root-cause decision.

| Ticket | Current decision |
|---|---|
| `T274` | Keep open. Source-crosscheck/release-gate discipline is ongoing process work. |
| `T276` | Keep open. Implementation subset exists, but broad evidence is delegated to `T283`. |
| `T280` | Keep open. Current-head full two-model prompt-bank audit remains missing. |
| `T281` | Keep open. UX exists, but broader sensitive-folder/private-mode proof remains open. |
| `T283` | Keep open. Broad log/artifact redaction audit remains a release gate. |
| `T284` | Keep open. Full current-head two-model audit results are still missing. |
| `T286` | Keep open. Backend smoke exists; full prompt bank still needs execution. |
| `T294` | Keep open as deferred beyond beta. Image/OCR remains future scope. |
| `T296` | Keep open. Private RAG gate exists; richer extraction provenance remains open. |
| `T299` | Keep open. Generated fixtures exist; larger maintained document corpus remains open. |
| `T300` | Keep open. Extraction limits exist; Windows performance/resource evidence remains open. |
| `T301` | Keep open. Docs exist; release-claim drift prevention remains open. |
| `T302` | Keep open as deferred beyond beta. PowerPoint remains intentionally unsupported. |
| `T303` | Keep open. Core state machine exists; dynamic encrypted/corrupt/limit propagation remains open. |
| `T304` | Keep open as deferred conditional cache work. |
| `T306` | Keep open. Synchronized runner exists; full prompt-bank integration remains open. |
| `T312` | Keep open. Native-tool prompt-bank coverage exists; candidate evidence remains open. |
| `T313` | Keep open. Piped approval fails closed; synchronized full prompt-bank path remains open. |
| `T319` | Keep open. First scenario bank exists; automation/live-model expansion remains open. |
| `T627` | Keep open. HtmlUnit inline fallback still exists; T626 made it causally honest but did not decide/remove the fallback. |

## New Tickets Created

Created three high-confidence open tickets because the latest static-web work
had confirmed ticket-track gaps.

| Ticket | Why it exists |
|---|---|
| `T696` static-web durable requirements continuation | The Qwen dirty continuation trace re-entered `FILE_CREATE`/`STATIC_WEB` but carried only `index.html` and `style.css`, no forbidden artifacts, and no durable required facts. Earlier prompt-debug had the full exact targets and required visible facts. |
| `T697` external frontend framework asset coherence | Current code is strong but Tailwind-specific. The product issue is generic: remote framework runtime, local generated/build artifact, and unsupported local placeholder must be classified consistently for frontend frameworks/assets. |
| `T698` static-web synchronized fresh/dirty audit packet | The latest audit root has useful Qwen evidence but empty `FINDINGS.md`, empty `LIVE-AUDIT.md`, header-only `MATRIX.csv`, partial transcripts, and incomplete model coverage. It can inform tickets but cannot close an audit gate. |

## Static-Web Evidence Basis

Useful audit evidence:

- `local/TalosTestOUTPUT/test02-10-post-t693-live-audit-20260605-105937/artifacts/qwen/prompt-debug/prompt-debug-20260606-063348.md`
  shows exact targets `index.html`, `style.css`, `script.js`, required visible
  facts including `Life span`, and forbidden artifacts `tailwind.css`,
  `tailwind.min.css`.
- `homes/qwen/.talos/sessions/.../000006-trc-dc4835a9-...json` shows dirty
  continuation classified as `FILE_CREATE`, `STATIC_WEB`, with expected targets
  only `index.html`, `style.css`, and no forbidden targets.
- `artifacts/qwen/dirty-final/index.html` still omits `Life span`.
- `StaticWebContentPreservationVerifier` can catch missing facts when the
  contract carries requirements; the dirty continuation gap is that the carried
  requirements were absent/thin.

Relevant code surfaces:

- `StaticWebRequirements`
- `ActiveTaskContext`
- `ActiveTaskContextPolicy`
- `JsonSessionStore`
- `CurrentTurnCapabilityFrame`
- `StaticWebContentPreservationVerifier`
- `StaticWebTailwindCoherenceVerifier`
- `StaticWebRemoteAssetVerifier`
- `RepairPolicy`

## Merge/Delete Decisions

No immediate merge is safe.

Potential future merges only after evidence closes:

- `T276` into `T283`, after broad redaction audit evidence is complete.
- `T284` into `T280`, after a current-head full two-model audit packet exists.
- `T313` into `T306` or `T312`, after synchronized full prompt-bank execution is
  reconciled.

No ticket should be deleted now.

## Bottom Line

The ticket registry is now more coherent:

- lifecycle metadata is consistent;
- old open tickets are mostly valid gates, not stale noise;
- recent static-web follow-up work is now ticketed as `T696`, `T697`, and
  `T698`;
- the next high-leverage product ticket is `T696`, followed by `T697`;
- the next audit gate is `T698`, but only after the implementation tickets are
  reviewed and deterministic checks pass.

