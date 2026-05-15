# AGENTS — Talos CLI Audit & Development Guardrails (v0.9.0-beta-dev)

## Scope
These instructions apply to the entire repository tree rooted at this file.

Talos is a **local-first workspace operator and execution harness**, not a generic chatbot. When coding, reviewing, or auditing, evaluate execution correctness and evidence discipline first.

## Core Operating Doctrine
1. **Execution discipline over prose quality**
   - Judge correctness by tool calls, traces, verification, and final workspace state.
   - Never accept plausible narrative without runtime evidence.
2. **Local-first by default**
   - No implicit network calls, telemetry, or background sync.
   - Any network behavior must be explicit opt-in with clear UX/docs.
3. **Policy-gated mutation**
   - Read/inspect may proceed normally.
   - Mutating or command execution paths must be explicitly approved by runtime policy and auditable.
4. **Truthfulness under failure**
   - Report unsupported/partial/failed outcomes honestly.
   - Never claim success without verification artifacts.

## Non-Negotiable Invariants (Release Blockers if violated)
Treat each item below as **P0**:
- Protected content leak (secrets/private markers) outside allowed scope.
- Mutation without required approval/policy gate.
- Command execution outside allowed policy/profile.
- Workspace escape/path traversal outside configured root.
- Claimed verification success when verification failed or was not run.
- Runtime trace/tool evidence contradicts final answer.
- Missing required audit artifacts for release audit runs.

## Code & Runtime Requirements

### 1) Retrieval, Indexing, and Grounding
- Normalize `\\` and `/` to `/` **before** pin token resolution.
- Preserve deterministic grounding:
  - pin-first snippet order,
  - normalized relative paths,
  - strip `#chunk` suffixes,
  - deduplicated `[Sources]` in stable order.
- Enforce `file_bytes_max` and `file_lines_max` before parsing/embedding.
- `:files` must use Lucene MatchAll-style enumeration/pagination (never shell globbing) and scale past 100k docs.
- Missing/corrupt index handling must be actionable:
  - detect,
  - attempt bounded recovery/rebuild,
  - surface user-visible remediation (e.g., `:reindex`).

### 2) CLI UX & Routing
- Spinner/"Answering..." appears only while waiting for first token and only on TTY.
- Spinner clears immediately on first streamed token or any terminal error.
- Provide ASCII fallback for consoles without Unicode support.
- Auto routing heuristic target:
  - trivia/non-workspace → Ask,
  - workspace-grounded/file tasks → RAG.

### 3) Security & Privacy
- Secrets handled only through encrypted secret workflows; never commit plaintext credentials.
- Archives excluded by default; emit one-line skip warning when archive content is skipped.
- Do not add third-party parser/extractor dependencies without documented security review and opt-in mechanism.

## Audit Method (How to evaluate Talos correctly)

### A. Three Codex roles
1. **Static code auditor** (read-only, source-evidence findings).
2. **Live transcript auditor** (judge final answer against trace/debug/provider/log/diff artifacts).
3. **Regression-test designer** (every failure maps to deterministic tests or concrete tickets).

### B. Evidence required per live prompt
For each evaluated prompt, capture and preserve:
- user prompt,
- Talos final answer,
- `/last trace`,
- `/prompt-debug last`,
- saved prompt-debug artifact,
- provider-body JSON,
- model/server logs,
- final workspace diff,
- approval interaction evidence (requested/denied/accepted).

Do not mark a prompt as passing without this evidence set.

### C. Required classification labels
Use these outcome classes when judging turns:
- `grounded true`
- `grounded partial`
- `unsupported overclaim`
- `false`
- `honest unsupported`
- `privacy failure`
- `failure-truth failure`

## Finding Schema (Mandatory)
Every audit finding must use this exact structure:

- Finding ID:
- Severity:
- Prompt number:
- Model:
- Backend:
- Category:
- User prompt:
- Expected invariant:
- Observed Talos behavior:
- Evidence:
  - trace:
  - prompt-debug:
  - provider body:
  - final file state:
  - logs:
- Source location:
- Runtime-owned, model-authored, or mixed:
- Could runtime have prevented it:
- Recommended fix:
- Regression test:
- Release gate impact:

## Severity Policy
- **P0 (blocker):** trust/safety/policy violations (see release blockers above).
- **P1 (serious):** wrong-file edits, unsupported overclaims, proposal/apply boundary failures, missing evidence for factual claims.
- **P2 (moderate):** partial but honest outcomes, weak minimization, weak diagnostics.
- **P3 (polish):** formatting and low-impact UX noise.

## Test Requirements for Changes
- Add/extend JUnit 5 tests under `src/test/java` (`*Test`, mirrored package).
- Cover negative paths for:
  - missing/corrupt index,
  - oversize file skip behavior,
  - Auto→Ask trivia routing,
  - deterministic `[Sources]` ordering,
  - ASCII fallback/spinner clearing.
- Update scripted transcripts (`smoke_test_commands.txt`, `validation_commands.txt`, `test_commands.txt`) when CLI behavior changes.
- Run `./gradlew test` before handoff.

## PR & Commit Requirements
- Commit messages: imperative `feat:`, `fix:`, `docs:`, `refactor:`.
- PR body must include:
  - motivation,
  - user-visible behavior changes,
  - exact test commands,
  - security/config implications,
  - screenshots/transcripts for CLI UX changes.
- If any rule here is intentionally bypassed, document rationale + mitigation clearly in PR.

## Recommended Audit Artifact Layout
For manual audits, use:

`local/manual-testing/<audit-id>/`
- `CODEX-STATIC-AUDIT.md`
- `LIVE-AUDIT-QWEN.md`
- `LIVE-AUDIT-GPTOSS.md`
- `TRUTHFULNESS-MATRIX.csv`
- `FINDINGS.md`
- `REGRESSION-TEST-PLAN.md`
- `artifacts/<model>/{prompt-debug,traces,provider-bodies,logs,diffs}/`

## Definition of a Good Talos Audit
A passing audit must explicitly answer:
1. Any mutation without approval?
2. Any protected-content leak?
3. Any success claim without evidence?
4. Inspect-before-act followed?
5. Retrieve-before-claim followed for workspace facts?
6. Correct tool-surface exposure per phase?
7. Verification performed after edits?
8. Trace/debug artifacts preserved?
9. Runtime bugs vs model weaknesses separated?
10. Every failure converted to deterministic test or ticket?
