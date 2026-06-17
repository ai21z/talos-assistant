---
name: trust-references
description: "Use when hardening or evaluating Talos's trust surface: secret detection and redaction, PII or private-document detection, audit-log integrity, path or sandbox containment, crypto-at-rest and key management, LLM or agent security standards, or verifying that the agent actually performed an action (tool-use hallucination). Points to the vetted source list and the downloaded references."
---

# Talos Trust References

When working on any Talos trust-surface hardening, ground the work in vetted sources, not memory.

## Where the material lives
- Full vetted source list AND the 54-item trust-overclaim register (each finding cited to code, severity-rated, with fix-or-disclose wording): `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`. Read the relevant section on demand.
- Downloaded papers and books: `../_reference/papers/` (outside the repo, not tracked). Place downloads from the source list here.
- Cloned comparison repos (claude-code, gemini-cli, hermes-agent, openclaw): `../_reference/reference-repos/` (design comparison only).

## How to use it
When fixing a specific trust gap, open the research doc, find the matching finding (ids like `secret-redaction-1`, `model-context-leak-paths-1`), and use the sources mapped to that topic. Nothing here is auto-loaded; read the specific file you need.

## Topic to canonical sources (compact map)
- Secret detection (beyond key=value regex): Yelp detect-secrets and gitleaks (entropy plus per-rule), TruffleHog (live verification), SecretBench (precision/recall benchmark), NIST SP 800-218.
- PII / private-document detection (replace the fixture canary): Microsoft Presidio, HIPAA Safe Harbor 18 identifiers, NIST SP 800-122, the Text Anonymization Benchmark (TAB).
- Tamper-evident audit logging: RFC 9162 (Certificate Transparency), Schneier-Kelsey secure audit logs (1999), in-toto, Sigstore/Rekor, IETF SCITT draft.
- File path and sandbox containment: CWE-22, CWE-59, CWE-367, SEI CERT FIO16-J, Microsoft Win32 path-naming (trailing dot, ADS, reserved device names), the access(2) TOCTOU papers.
- Crypto-at-rest and key management: NIST SP 800-57, SP 800-38D (GCM nonce rules), OWASP Cryptographic Storage and Key Management cheat sheets, Windows DPAPI and Credential Manager. Books: Serious Cryptography 2e (buy), Security Engineering 3e (free online).
- LLM and agent security standards: OWASP Top 10 for LLM Applications 2025 and the Agentic Top 10, NIST AI 600-1, MITRE ATLAS, Google SAIF.
- Tool-use hallucination and action verification (the headline asset): AgentHallu (the 11.6% step-localization figure), MIRAGE-Bench, ToolBeHonest, tau-bench (deterministic end-state check, not an LLM judge), the "Tool Receipts" paper.

Every source URL, trust tier, access note (free or buy), and the reason it helps are in the research doc.
