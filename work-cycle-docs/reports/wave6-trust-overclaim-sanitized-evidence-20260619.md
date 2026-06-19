# Wave 6 Trust-Overclaim Sanitized Evidence Record (2026-06-19)

Status: sanitized tracked evidence record
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Source class: derived from local raw audit evidence, with exploit walkthroughs
and private operational provenance removed.

## Purpose

This report is the remote-safe, tracked evidence record for the Wave 6
trust-surface work. It replaces the need for active tickets to depend on the
ignored raw research file:

`work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`

The raw audit remains local research evidence. It is intentionally not promoted
as-is because it contains exploit-oriented failure walkthroughs, detailed
reproduction notes, and internal operator/provenance detail that are not needed
for durable ticket tracking.

## Sanitization Boundary

Kept:

- finding IDs;
- severity;
- affected trust surface;
- bounded issue statement;
- ticket mapping;
- fix direction;
- honest disclosure themes;
- external-source categories at a non-sensitive level.

Removed or collapsed:

- `Failure mode:` walkthroughs;
- most line-by-line reproduction detail;
- token-shaped examples beyond generic class names;
- local first-person provenance;
- agent/scout process provenance;
- raw provider-body, transcript, log, or local artifact payload detail.

## Forensic Review Summary

The raw audit was reviewed before this sanitized record was created.

- No plausible real credential or live token was found.
- Token-shaped hits were deliberate audit examples, fake fixtures, placeholders,
  or false positives inside words/paths.
- No host-local absolute path leak was found.
- No full raw provider-body JSON, full transcript, or full log payload was
  found copied into the raw file.
- The reason not to promote the raw file is operational/exploit density, not a
  confirmed secret leak.

## Executive Summary

The raw audit tested 73 trust/privacy/security/truthfulness claims.

Result:

- 54 confirmed or partially mitigated overclaims.
- 19 refuted claims.
- Confirmed severity split: 5 high, 19 medium, 30 low.

The immediate product direction remains:

1. T833: disclose current trust boundaries honestly.
2. T834-T838: close the five high trust gaps before public push.
3. Defer broader capability work until the trust surface is no longer
   overclaimed.

## High-Priority Code-Fix Map

| Ticket | Primary audit IDs | Trust gap | Required direction |
| --- | --- | --- | --- |
| T834 | `secret-redaction-1`, `secret-redaction-2`, `secret-redaction-3`, `secret-redaction-4`, `secret-redaction-6`, `model-context-leak-paths-2` | Redaction differs by sink and misses standalone credential-like values. | Use one stronger sanitizer/redactor path for model context, durable artifacts, logs, indirect reads, and related sink paths. Test through the real handoff and persistence paths. |
| T835 | `local-first-offline-truth-1`, `local-first-offline-truth-2`, `local-first-offline-truth-4` | Chat transport locality is described more strongly than the current endpoint enforcement. | Default-deny non-local chat hosts unless explicitly configured and visibly disclosed. |
| T836 | `protected-path-classification-1`, `protected-path-classification-2`, `protected-path-classification-3`, `protected-path-classification-4`, `sandbox-escape-4`, `sandbox-escape-5` | Protected-path and sandbox classification have Windows/path-normalization gaps. | Normalize Windows path edge cases and classify the effective target before read/mutation policy decisions. |
| T837 | `model-context-leak-paths-1` | Command output is model-visible unless only best-effort text redaction catches it. | Give command output explicit content metadata and route it through the privacy handoff or a summarize/withhold policy. |
| T838 | `secret-vault-and-api-keys-1`, `secret-vault-and-api-keys-2`, `secret-vault-and-api-keys-3`, `secret-vault-and-api-keys-4`, `secret-vault-and-api-keys-5` | The local secret store is not OS-backed key custody and should not be positioned as such. | Move key custody to an OS-backed or passphrase-derived wrapping scheme and keep public wording bounded until then. |

## Required Honest Disclosure Themes

These themes are safe to publish now because they bound the current code instead
of promising future behavior:

- Talos's deterministic no-change/no-success correction is strongest for
  file-mutation turns. `run_command` claims and factual read/answer claims are
  not equivalently covered yet.
- Current redaction catches common key=value secret shapes and known canaries;
  it is not complete standalone-token, JWT, PEM, connection-string, or
  high-entropy detection.
- `run_command` output can enter model context after best-effort textual
  redaction; it is not withheld by default.
- Windows protected-path classification needs additional canonicalization for
  path spellings that the OS resolves differently than Java's lexical path.
- Chat model endpoints are not yet guarded as strictly as the local-first claim
  requires.
- Current local encryption is casual-inspection protection until raw key custody
  moves away from ciphertext-adjacent file storage.
- Local traces and logs are durable diagnostics, not tamper-evident audit logs.

## Confirmed Finding Index

### High

| ID | Sanitized issue | Track |
| --- | --- | --- |
| `secret-redaction-1` | Redaction on model handoff and durable sinks is narrower than public trust wording. | T834 |
| `protected-path-classification-1` | Windows path spellings can defeat exact protected-name matching. | T836 |
| `model-context-leak-paths-1` | Command output lacks an explicit withhold/summarize model-handoff boundary. | T837 |
| `local-first-offline-truth-1` | Chat transport accepts configured remote hosts without the locality guard implied by local-first copy. | T835 |
| `secret-vault-and-api-keys-2` | The master key remains stored beside encrypted entries; this is not OS-backed key custody. | T838 |

### Medium

| ID | Sanitized issue | Track |
| --- | --- | --- |
| `secret-redaction-2` | Sink redaction strength is inconsistent across prompt-debug, model context, traces, sessions, and logs. | T834 |
| `secret-redaction-3` | Quoted structured secret labels can miss the current key=value redaction shape. | T834 |
| `secret-redaction-4` | Assistant prose persistence relies on narrow redaction triggers. | T834 |
| `secret-redaction-6` | Exception/error diagnostic text can bypass stronger redaction paths. | T834 |
| `protected-path-classification-2` | Common credential filenames are not all covered by current protected-name heuristics. | T836 |
| `sandbox-escape-5` | Fallback path resolution can become lexical rather than real-target based. | T836 |
| `approved-equals-written-4` | New-file approval preview is intentionally capped and should not be described as a full-content approval. | Deferred |
| `audit-trace-integrity-2` | Some failed/errored/empty turns can leave trace gaps. | Deferred |
| `model-context-leak-paths-2` | Normal tool metadata can degrade model handoff to text-shape sanitization. | T834 |
| `model-context-leak-paths-5` | Indirect read outputs in default developer mode rely on limited text sanitization. | T834 |
| `anti-overclaim-completeness-2` | Command-success claims are not comprehensively verified on every turn type. | T837 |
| `secret-vault-and-api-keys-1` | The "secret vault" is not yet the provider-auth path. | T838 |
| `secret-vault-and-api-keys-3` | Windows master-key file custody is not the same as OS-backed secret custody. | T838 |
| `evidence-gate-discipline-meta-1` | Some ledger checks validate shape more than semantic PASS correctness. | Deferred |
| `evidence-gate-discipline-meta-2` | Some evidence-path references are not verified as live artifacts. | Deferred |
| `evidence-gate-discipline-meta-6` | Canary scanning does not equal general secret scanning. | T834 |
| `evidence-gate-discipline-meta-7` | "Release gate" ticket labels are not yet fully enforced metadata. | Deferred |
| `docs-site-claims-vs-code-1` | Site honesty checks are separate from the Java/Gradle gate. | Deferred |
| `docs-site-claims-vs-code-2` | Sanitization wording should stay bounded to current redaction capability. | T833/T834 |

### Low

| ID | Sanitized issue | Track |
| --- | --- | --- |
| `protected-path-classification-3` | Directory enumeration policy is weaker than some protected-path wording. | T836 |
| `protected-path-classification-4` | Unicode/homoglyph path tricks are not a solved protected-path guarantee. | T836 |
| `sandbox-escape-1` | Some documented sandbox config concepts are not the active enforcement path. | Deferred |
| `sandbox-escape-4` | Case normalization for new-file deny matching has edge cases. | T836 |
| `approved-equals-written-1` | Diff preview and edit execution use separate logic paths. | Deferred |
| `approved-equals-written-2` | There is no post-write approval-hash readback proof. | Deferred |
| `approved-equals-written-5` | Preview and write read different file snapshots unless separately guarded. | Deferred |
| `audit-trace-integrity-1` | Trace JSON is durable local evidence, not tamper-evident evidence. | Deferred |
| `audit-trace-integrity-3` | JSONL append mode is not cryptographic append-only protection. | Deferred |
| `audit-trace-integrity-4` | Unsalted content hashes should not be sold as privacy-preserving proof. | Deferred |
| `audit-trace-integrity-6` | Trace coverage is strongest in the tool-loop path. | Deferred |
| `model-context-leak-paths-3` | Path-name based withholding cannot find every sensitive value in ordinary files. | T834 |
| `model-context-leak-paths-6` | Tool error text needs the same trust-boundary discipline as normal tool output. | T834 |
| `anti-overclaim-completeness-1` | File-mutation anti-overclaim enforcement is stronger than read/answer factual enforcement. | T833/T837 |
| `anti-overclaim-completeness-3` | Some content claims remain unverified outside narrow readback shapes. | T833 |
| `anti-overclaim-completeness-4` | Streaming truthfulness controls are bounded and phrase/transport dependent. | T833 |
| `config-fail-open-3` | Config merge/default behavior can drop nested safe defaults if users replace whole maps. | Deferred |
| `config-fail-open-5` | Required/named tool-choice obligations degrade when the backend lacks that feature. | Deferred |
| `local-first-offline-truth-2` | Network labels are not enforcement by themselves. | T835 |
| `local-first-offline-truth-4` | Wording such as "intended" and "expected" must not substitute for enforcement claims. | T835 |
| `secret-vault-and-api-keys-4` | Secret-store stub status needs stronger tests before trust positioning expands. | T838 |
| `secret-vault-and-api-keys-5` | IDE fallback for secret entry needs honest UX/security wording. | T838 |
| `evidence-gate-discipline-meta-3` | Manual lanes should not read like automated evidence. | Deferred |
| `evidence-gate-discipline-meta-4` | Coverage gate copy must match the actually enforced threshold. | Deferred |
| `evidence-gate-discipline-meta-5` | Qodana status should remain explicit when not run or stale. | Deferred |
| `evidence-gate-discipline-meta-8` | Architecture-boundary regex reports are advisory beside bytecode-level checks. | Deferred |
| `evidence-gate-discipline-meta-9` | Wiki evidence liveness is a separate close gate, not ordinary `check`. | Deferred |
| `evidence-gate-discipline-meta-10` | Fail-soft evidence lanes need explicit interpretation. | Deferred |
| `docs-site-claims-vs-code-3` | Marketing-copy bans are not full semantic trust verification. | T833 |
| `docs-site-claims-vs-code-4` | Site copy must not imply private-document guarantees stronger than current default mode. | T833 |

## External Source Categories

The raw audit included vetted source categories. This sanitized record keeps the
categories without preserving agent-scout provenance:

- secret scanning and entropy/verification-based credential detection;
- PII/private-document detection and de-identification;
- agent/tool-use hallucination and action attribution;
- tamper-evident logs, software supply-chain evidence, and audit trails;
- local-first/privacy/security standards;
- cryptographic storage and OS-backed key custody.

Before public copy cites any specific external paper, standard, or competitor,
re-check the primary source and keep claims scoped to what that source actually
supports.

## Promotion Decision

Promote this sanitized record as the tracked Wave 6 evidence artifact.

Do not promote the raw audit file as-is. Keep it local unless the owner later
requests a full redaction rewrite of the raw material.
