# T845 Beta Model And Hardware Range Evidence

Status: waiting-on-T842-manual-evidence
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Created: 2026-06-21

## Decision State

No beta hardware range is ratified by this report yet.

T842 full manual testing is running in parallel. This report is the tracked
schema for the evidence we need before public beta hardware and model wording
is credible.

## Current Provisional Wording

Use only the following provisional language until T842 evidence is accepted:

- Minimum practical path: BM25-only retrieval plus one configured local chat
  model.
- Recommended path: managed `llama.cpp`, one of the audited Qwen/GPT-OSS
  profiles, SSD storage, enough RAM for the selected GGUF, and a local
  embedding endpoint only if vector retrieval is desired.
- Unproven path: low-RAM machines, unknown GPUs, remote endpoints, private
  paperwork folders, OCR workflows, and PowerPoint workflows.

If the accepted evidence covers only one machine, say so. Do not convert a
single-machine pass into a broad hardware matrix.

## Evidence Fields To Fill

| Field | Required value |
| --- | --- |
| Audit packet | T842/manual evidence root and accepted report |
| Branch | `git branch --show-current` from the evidence run |
| Commit | `git rev-parse HEAD` from the evidence run |
| Talos version | `talosVersion` from the evidence run |
| OS | Version and architecture |
| CPU | Model or family when captured |
| RAM | Installed or OS-visible memory when captured |
| Disk | SSD/HDD/NVMe when captured |
| GPU/VRAM | Only if reliably probed or observed |
| Backend | Managed `llama.cpp`, Ollama, or other |
| Model A | Qwen profile and runtime model identity, or explicit absence |
| Model B | GPT-OSS profile and runtime model identity, or explicit absence |
| Embeddings | Provider/model/host locality and success/failure |
| Retrieval mode | Hybrid or BM25-only for each relevant run |
| Startup latency | Observed, not estimated |
| Indexing latency | Observed, not estimated |
| Retrieval latency | Observed, not estimated |
| Turn latency | Observed, not estimated |
| Failures | Findings and contaminated evidence |

## Acceptance Rule

This report can be promoted from `waiting-on-T842-manual-evidence` only after
the T842 packet identifies the hardware, models, backend, retrieval state, and
observed latencies or explicitly records that those values were not captured.

Unknown is acceptable. Invented is not.
