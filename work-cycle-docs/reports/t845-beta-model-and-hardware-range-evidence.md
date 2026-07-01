# T845 Beta Model And Hardware Range Evidence

Status: accepted
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Created: 2026-06-21

## Decision State

No broad beta hardware range is ratified by this report.

This report records the first accepted T842/independent review manual evidence for beta
model and hardware wording. It is intentionally narrow: the evidence covers one
Windows 11 machine, two managed `llama.cpp` chat models, and BM25-only
retrieval. It does not validate a general hardware matrix or hybrid/vector
retrieval quality.

Owner/independent review review accepted this report on 2026-06-21 as a narrow beta-range
evidence snapshot. The broader T842 manual audit remains open.

## Evidence Source

Accepted local T842 summaries:

- `local/beta-pre-release-test-scenarios/SUMMARY.md`
- `local/beta-pre-release-test-scenarios/SUMMARY-batch2.md`
- `local/beta-pre-release-test-scenarios/SUMMARY-gpt-oss-vs-qwen.md`

These local summaries are not promoted as release-clean tracked artifacts.
They are the accepted owner/independent review evidence packet for this first beta-range
snapshot.

## Beta Wording Allowed From This Evidence

- Minimum practical path: BM25-only retrieval plus one configured local chat
  model.
- Recommended path: managed `llama.cpp`, one of the audited Qwen/GPT-OSS
  profiles, SSD storage, enough RAM for the selected GGUF, and a local
  embedding endpoint only if vector retrieval is desired.
- Unproven path: low-RAM machines, unknown GPUs, remote endpoints, private
  paperwork folders, OCR workflows, and PowerPoint workflows.

Important retrieval caveat: Talos ships with `rag.vectors.enabled: true`, but
the accepted T842 beta evidence exercised BM25-only retrieval. Hybrid/vector
retrieval was not validated because no working local embedding endpoint was
part of the accepted run.

## Evidence Matrix

| Field | Accepted evidence |
| --- | --- |
| Audit packet | T842 local beta pre-release scenario summaries listed above |
| Branch | `v0.9.0-beta-dev` in ticket context |
| Commit | Not captured in the local summaries; current implementation base before this report was `496e2b521417c28fad7ea21d05fe2912ed07ff35` |
| Talos version | `0.10.5`; summaries also state Talos 0.10.5 clean build/install |
| OS | One Windows 11 machine, per accepted independent review/T842 review |
| CPU | Not captured in accepted summaries |
| RAM | Not captured in accepted summaries |
| Disk | Not captured in accepted summaries |
| GPU/VRAM | Not reliably probed or captured |
| Backend | Managed `llama.cpp` |
| Model A | `qwen2.5-coder-14b` |
| Model B | `gpt-oss-20b` |
| Embeddings | No working local embedding endpoint was part of the accepted evidence |
| Retrieval mode | BM25-only for the accepted run; hybrid/vector path not validated |
| Startup latency | Not captured in accepted summaries |
| Indexing latency | Not captured in accepted summaries |
| Retrieval latency | Not captured in accepted summaries |
| Turn latency | Approximately 5-12s observed in accepted review; no per-turn timing table in the local summaries |
| Failures | No trust break in either model; systemic classifier gap for `fix <thing> in <file>`; systemic absent named-target handling gap; qwen-specific grounding weakness; gpt-oss-specific multi-read loop/read-display echo issues |

## Scenario Evidence Summary

The accepted summaries cover 15 scenarios on both audited models.

- Qwen batch 1: 6/6 pass for PDF, Excel, DOCX, data files, code explanation,
  and create-empty-workspace.
- Qwen batch 2: 6/9 full pass; trust surface held in all 9; three
  model/prompt/classifier quality gaps were recorded.
- Cross-model run: qwen and gpt-oss both show 12 PASS / 2 FAIL / 1 MIXED over
  scenarios 1 through 15.
- Trust surface held across both models: deterministic unsupported-format
  refusal, anti-overclaim file-mutation guard, checkpoints, tool-surface
  narrowing, loop guard, privacy redaction, and honest verification status.
- Capability evidence supports beta claims for text-bearing PDF, DOCX, Excel,
  JSON/CSV, code explanation, file creation, precise edit, and delete in this
  scenario bank.
- Unsupported PowerPoint and image OCR are honest refusals, not beta
  capabilities.

## Acceptance Rule

This report is accepted only for its narrow evidence boundary. Unknown hardware
and timing fields stay unknown. Hybrid/vector retrieval stays unvalidated until
a later run includes a working local embedding endpoint and records
retrieval-lane behavior.

Unknown is acceptable. Invented is not.

## Closeout

- Accepted source: owner/independent review review of the T842 local scenario summaries.
- Population commit: `d35f708f34fc08e3b406afe217ec07f862c493a8`.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed during review.
- Follow-up beta-correctness findings from T842 are tracked separately as
  T848-T852.
