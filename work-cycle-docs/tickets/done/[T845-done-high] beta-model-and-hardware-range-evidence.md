# [T845-done-high] Beta Model And Hardware Range Evidence

Status: done
Priority: high
Type: research-report
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Define beta model and hardware support language from actual T842/manual
evidence instead of speculation. This ticket must not invent minimum
requirements from desired positioning.

## Current State

Status: done

The first accepted T842/Opus manual evidence has been summarized into the
report and accepted for this narrow beta model/hardware range snapshot. T842
itself remains open because the full owner-run audit packet and interactive
evidence are broader than this report.

## Evidence Required

The report must record:

- hardware actually tested;
- OS, CPU, RAM, disk type when known, and relevant GPU/VRAM only if reliably
  observed;
- model backend, profile, runtime model identity, and whether managed
  `llama.cpp` was used;
- Qwen `qwen2.5-coder:14b` and GPT-OSS `gpt-oss:20b` coverage or explicit
  absence;
- startup, indexing, retrieval, and turn-latency observations;
- embedding availability and whether retrieval ran hybrid or BM25-only;
- observed failures, contaminated evidence, or unproven paths.

## Beta Wording To Derive

The final T845 report should classify:

- minimum practical path: BM25-only retrieval plus one configured local chat
  model;
- recommended path: managed `llama.cpp`, a tested Qwen/GPT-OSS profile, SSD,
  enough RAM for the chosen GGUF, and a local embedding endpoint if vector
  retrieval is desired;
- unproven path: low-RAM machines, unknown GPUs, remote endpoints, private
  paperwork folders, OCR workflows, and PowerPoint workflows.

If only one machine is tested, the report must say that and mark any hardware
matrix as provisional.

## Non-Goals

- No production code.
- No release requirements without evidence.
- No model swap.
- No vector database or retrieval architecture change.
- No public beta claim that a machine class is supported unless it appears in
  accepted evidence.

## Initial Report

See `work-cycle-docs/reports/t845-beta-model-and-hardware-range-evidence.md`.

## Evidence Populated 2026-06-21

- Source packet:
  - `local/beta-pre-release-test-scenarios/SUMMARY.md`
  - `local/beta-pre-release-test-scenarios/SUMMARY-batch2.md`
  - `local/beta-pre-release-test-scenarios/SUMMARY-gpt-oss-vs-qwen.md`
- The accepted evidence covers one Windows 11 machine.
- Both audited models ran through managed `llama.cpp`:
  - `qwen2.5-coder-14b`
  - `gpt-oss-20b`
- The accepted retrieval evidence is BM25-only. Talos ships with
  `rag.vectors.enabled: true`, but the accepted T842 run did not validate
  hybrid/vector retrieval because no working local embedding endpoint was part
  of the evidence packet.
- CPU, RAM, disk type, GPU/VRAM, startup latency, indexing latency, and
  retrieval latency were not captured in the accepted summaries and remain
  unratified.

## Completion Evidence

- Accepted evidence source: owner/Opus review of the local T842 scenario
  summaries.
- Population commit: `d35f708f34fc08e3b406afe217ec07f862c493a8`.
- The report preserves the required caveats: one Windows 11 machine, managed
  `llama.cpp` Qwen/GPT-OSS coverage, BM25-only retrieval, no validated
  hybrid/vector run, and unknown hardware/timing fields left unknown.
- `wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed during review of
  the populated report.
- Closeout batch creates separate beta-correctness tickets for the T842
  executor findings instead of letting T847 retrieval work absorb them.
