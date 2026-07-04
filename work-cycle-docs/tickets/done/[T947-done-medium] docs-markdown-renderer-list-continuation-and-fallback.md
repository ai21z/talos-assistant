# [T947-done-medium] Docs Markdown renderer list continuation and fallback

Status: done
Priority: medium

## Summary

Improve the in-site docs Markdown renderer so normal wrapped Markdown list
items render as a single list item instead of splitting continuation lines into
separate paragraphs. Also review the docs page's JS-rendered primary content
fallback so the page does not look blank or broken during initial render or
when scripts fail.

## Evidence

Renderer ownership:

- `site/src/docs.js:90` defines the narrow `renderMarkdown` implementation.
- `site/src/docs.js:159` handles unordered lists by consuming only consecutive
  lines that each start with `-`.
- `site/src/docs.js:170` handles ordered lists the same way.
- `site/src/docs.js:187` then treats non-blank continuation lines as ordinary
  paragraphs.

Source docs use normal wrapped Markdown list items. Examples:

- `docs/user/quickstart.md:24-25` wraps the Ubuntu/WSL prerequisite bullet.
- `docs/user/quickstart.md:26-27` wraps the direct/expert server-path bullet.
- `docs/user/model-setup.md:27-29` wraps the pinned-engine wizard bullet.
- `docs/user/model-setup.md:32-34` wraps the model-download guidance bullet.
- `docs/user/installation.md:13-15` wraps the Ubuntu/WSL setup bullet.

Rendered behavior verified locally:

- On `/docs.html#/quickstart`, the `llama.cpp` continuation line from the
  Ubuntu/WSL prerequisite bullet renders outside the bullet flow as a separate
  paragraph-like fragment.

Fallback/client-render evidence:

- `site/docs.html:98-108` ships an empty `#docs-article` with a `noscript`
  fallback; `docs.js` fills primary content client-side.
- This is acceptable for the current beta if deliberate, but should be tested
  and styled so the docs page does not appear as an empty black article before
  JavaScript route rendering.

## Implementation Direction

- Extend `renderMarkdown` list parsing to support continuation lines indented
  under the previous list item.
- Preserve the intentionally small renderer: do not introduce a large Markdown
  dependency unless the narrow implementation becomes too fragile.
- Add tests that render a wrapped list item and assert the continuation stays
  inside the same `<li>`.
- Add a docs-page fallback/loading test or static guard so the initial
  `docs.html` shell has a useful no-script/loading state and no permanent blank
  region if JS fails.

## Acceptance Criteria

- Wrapped list items in Quickstart, Installation, and Model Setup render as
  coherent list items.
- Existing code-block, table, heading, link-rewrite, and copy-button behavior
  remains intact.
- Tests fail before the list-continuation fix and pass after it.
- If JavaScript is disabled or delayed, the docs page presents an honest
  fallback rather than appearing as an unexplained blank content area.

## Verification

Required focused checks:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
```

Rendered smoke:

- `/docs.html#/quickstart`
- `/docs.html#/model-setup`
- `/docs.html#/installation`

Run `git diff --check` before closeout.

## Completion Evidence

Completed on `v0.9.0-beta-dev` for the 0.10.8 public-artifact preparation
batch.

- Extracted the narrow docs Markdown renderer into `site/src/docs-markdown.js`
  so renderer behavior can be unit-tested directly without a browser route.
- Added renderer tests for wrapped unordered and ordered list items; both guard
  against continuation text escaping into separate paragraphs.
- Updated `docs.js` to call `renderMarkdown(md, { currentSlug })`, preserving
  same-page Markdown anchor rewriting after the extraction.
- Added an honest initial docs article fallback in `docs.html` so the page is
  not an unexplained blank if JavaScript is disabled or delayed.
- Rendered preview smoke verified Quickstart, Model Setup, and Installation
  continuation text stays inside list items, and verified the fallback text is
  visible with JavaScript disabled.

## Release Gate Impact

Pre-public-artifact docs quality gate. Not a runtime safety blocker, but it is
visible documentation correctness and readability.
