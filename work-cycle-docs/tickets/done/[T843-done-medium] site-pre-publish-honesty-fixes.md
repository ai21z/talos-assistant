# [T843-done-medium] Site Pre-Publish Honesty Fixes

Status: done
Priority: medium
Type: site-honesty
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Purpose

Track the marketing-site honesty items found in the 2026-06-21 site review. The site
is committed as-is to dev (nothing is published, local branch only). These items must
be resolved before any public publish, not before the dev commit. `site/` is
owner-managed work, so these are owner or fix-track changes, not audit-track changes.

The site passes its own 33-test honesty suite and builds clean today. These are
items the test suite does not catch or that are softer framing.

## Scope

1. Privacy overclaim, the load-bearing one: "keeps every turn on your machine" is an
   absolute that the code contradicts, because `HostLocalityPolicy` allows a remote host
   when `allow-remote` is set. Soften to "by default" or "local by default". Appears at
   `site/index.html:402` and `site/src/main.js:582`.
2. "One ordered flow. No skipped steps." (`site/index.html:223`). A read-only or ask turn
   skips Approve and Mutate. Reword so it does not claim every step runs every turn.
3. "Protected paths require explicit approval" (`site/index.html:349`). True for the
   defined set only. Prefer "defined protected paths". The trust audit flagged this.
4. Align "Windows-first beta" (`site/index.html:469`, `site/docs.html:122`,
   `site/src/docs.js:210` and `:345`) with the hero "planned public beta" so the site
   does not imply a live public beta before one exists.
5. winget install block: keep as-is for now per owner decision. Revisit when a real public
   installer ships. The honesty suite already requires the "planned public beta" framing
   and bans "winget install works now", so the planned-labeled block is acceptable until
   then.
6. Wire the `site/test` static honesty suite into CI or the release checklist so the front
   door cannot regress silently (the suite runs manually today only).

## Acceptance Criteria

- Items 1 through 4 reworded to the honest-bounded wording, and item 6 wired into a gate
  or the release checklist.
- `cd site; npm test` stays green and the new wordings are asserted where practical.
- `cd site; npm run build` succeeds.
- No capability claim regresses (PDF/DOCX/XLSX now, PowerPoint/OCR/sensitive-docs v1).

## Implementation State

Status: done

- Items 1 through 4 were reworded in `site/index.html`, `site/docs.html`,
  `site/src/main.js`, and `site/src/docs.js`.
- The site honesty suite is now wired into `.github/workflows/beta-dev-ci.yml`
  before the Java checks, with `npm ci --prefix site`, `npm test --prefix site`,
  and `npm run build --prefix site`.
- The static site test suite now pins the bounded wording and bans the old
  absolute "every turn" claims.
- Verification on 2026-06-21:
  - `npm test` from `site/`: 33 tests, 0 failures.
  - `npm run build` from `site/`: build successful.
- Owner accepted the revised site copy on 2026-06-21.
- Implementation commit:
  `496e2b521417c28fad7ea21d05fe2912ed07ff35`.
- Review verification on 2026-06-21:
  - focused docs/doctor tests passed;
  - `.\gradlew.bat check --no-daemon` passed;
  - `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed;
  - `git diff --check` passed.

## Non-Goals

- Not a pre-commit blocker. The site is committed to dev as-is.
- Do not claim a live public installer or live winget until one actually ships.
- Do not position the beta for tax, health, legal, or private-folder paperwork.
