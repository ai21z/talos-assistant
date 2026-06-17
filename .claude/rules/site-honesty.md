---
paths:
  - "site/**"
---

# Talos site honesty guardrails

The marketing site is the highest overclaim-risk surface, and the whole Talos pitch is "the local assistant you can prove." Editing it under honest constraints is mandatory.

Full doctrine: `AGENTS.md` sections Beta Scope And Capability Boundaries, Truthfulness Doctrine. Source-of-truth memory: the Talos site and file-capability-truth notes. Trust posture: `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`.

Never weaken these honesty guardrails (the `site/test` suite enforces several of them, do not delete those assertions):
- No fabricated or aspirational install command. Installation copy must reflect the real, verified path. Treat winget as planned, not live, unless that has actually shipped.
- Any version shown on the site must equal `talosVersion` in `gradle.properties`.
- Links are GitHub-only or other verified destinations. No fake or broken external links.
- Capability claims must match shipped reality: text-bearing PDF, DOCX, and XLS/XLSX extraction work now. PowerPoint, legacy `.doc`, image OCR, and the sensitive-paperwork workflow are NOT beta claims. Do not position the beta as safe for tax, health, legal, or private-folder paperwork.
- Trust-surface claims (secret redaction, "what it did equals what you approved", audit trail, privacy) must not exceed what the code actually delivers. The trust audit found several claims currently outrun the code, so prefer the honest-disclosure wordings there over confident absolutes.

Design and layout are free to change. The honesty guardrails above are not.
