# [T845-open-high] Beta Model And Hardware Range Evidence

Status: open
Priority: high
Type: research-report
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Define beta model and hardware support language from actual T842/manual
evidence instead of speculation. This ticket must not invent minimum
requirements from desired positioning.

## Current State

Status: waiting-on-T842-manual-evidence

T842 full manual testing is running in parallel. Until the T842 evidence packet
is accepted, this ticket can define the evidence schema and provisional wording
only.

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
