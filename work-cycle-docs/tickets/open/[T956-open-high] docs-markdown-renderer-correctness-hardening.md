# [T956-open-high] Docs Markdown renderer must not corrupt public documentation content

Status: open
Priority: high

## Evidence Summary

- Source: external rendered-site review plus local renderer probes
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Talos version / commit: 0.10.8 /
  `9d7174ee9129c0a566d3a1656adf6d7894f54f5d`
- Code under review:
  - `site/src/docs-markdown.js`
  - `site/test/docs-renderer.test.js`
- Verification status: confirmed by direct renderer execution

Probe input:

```text
Use `code` and plain 2 * 3 plus *.md glob.
[wiki](https://example.com/a_(b))
| A | B |
|---|---|
| `a|b` | ok |
```

Observed rendered output:

```html
<p>Use <code>code</code> and plain 2 <em> 3 plus </em>.md glob.</p>
<p><a href="https://example.com/a_(b" target="_blank" rel="noopener">wiki</a>)</p>
<td>`a</td><td>b`</td><td>ok</td>
```

Expected behavior:

```text
The docs renderer may remain small and trusted, but it must not silently
corrupt ordinary documentation text, URLs, or table cells. If a Markdown shape
is supported, it must render correctly. If a shape is intentionally unsupported,
it should degrade as literal escaped text rather than producing misleading
HTML.
```

## Classification

Primary taxonomy bucket: `PUBLIC_DOCS_TRUTH`

Secondary buckets:

- `SITE_RENDERING`
- `PUBLIC_ARTIFACT_GATE`

Blocker level: public-artifact blocker

Why this level:

```text
The website documentation is the public install and beta-support front door.
The docs are source-backed, but the current renderer can transform true source
Markdown into false rendered documentation. That must be fixed before treating
the staged public artifacts as ready for external users.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
`site/src/docs-markdown.js` is intentionally a small renderer, but its inline
regexes parse emphasis, links, and table cells without enough tokenization.
This makes ordinary prose and code-like docs text ambiguous. The missing guard
is a renderer contract over real docs plus representative edge cases.
```

Likely code/document areas:

- `site/src/docs-markdown.js`
- `site/test/docs-renderer.test.js`
- `site/test/site.test.js`

Why a one-off patch is insufficient:

```text
Fixing only the observed examples would leave the same drift class open. The
test suite should render all current `docs/user/**/*.md` sources and pin the
renderer's supported Markdown subset, including safe fallback for unsupported
shapes.
```

## Goal

```text
Public docs rendering is deterministic, escaped, and content-preserving for
the Markdown shapes Talos docs actually use.
```

## Non-Goals

- No runtime Talos behavior changes.
- No replacing the docs architecture with runtime fetching.
- No broad framework migration.
- No accepting raw HTML passthrough.
- No adding a large Markdown dependency unless the evidence shows the small
  renderer cannot be made correct enough for the public docs subset.

## Implementation Notes

- Start with failing tests in `site/test/docs-renderer.test.js` for:
  - arithmetic/glob asterisks (`2 * 3`, `*.md`) not becoming emphasis,
  - parenthesized external URLs,
  - inline-code pipes inside Markdown tables,
  - query-string and relative `.md` links resolving or degrading deliberately.
- Add a real-doc render smoke that renders every `docs/user/**/*.md` file and
  rejects obvious broken output patterns.
- Keep HTML escaping and unsafe-protocol handling intact.

## Architecture Metadata

Capability:

- public documentation rendering

Operation(s):

- static site build/render only

Owning package/class:

- `site/src/docs-markdown.js`

New or changed tools:

- none expected

Risk, approval, and protected paths:

- Risk level: high for public documentation truth, none for workspace mutation
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: rendered HTML must preserve source documentation
  meaning
- Verification profile: Node static tests plus site build
- Repair profile: no model/runtime repair

Outcome and trace:

- Outcome/truth warnings: public docs must not render false install or support
  information
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: focused renderer tokenization helpers and tests
- Forbidden: broad site redesign

## Acceptance Criteria

- Renderer tests cover and pass for the observed corruption cases.
- Every current `docs/user/**/*.md` file renders without obvious parser
  corruption.
- External links with balanced parentheses keep the full href.
- Inline code containing `|` does not split table cells.
- Plain arithmetic/glob asterisks remain literal unless they are valid
  emphasis markers.
- Existing HTML escaping and unsafe-protocol defenses remain covered.

## Tests / Evidence

Required deterministic regression:

- Unit test: `site/test/docs-renderer.test.js`
- Static contract: `site/test/site.test.js` if real-doc coverage belongs there

Commands:

```powershell
npm test --prefix site
npm run build --prefix site
```

## Known Risks

- A full Markdown parser could increase dependency and supply-chain surface.
  Prefer targeted correctness in the current trusted subset unless evidence
  proves that route is less reliable.

## Known Follow-Ups

- If docs begin using richer Markdown shapes such as blockquotes, task lists,
  images, footnotes, h5/h6, or multi-paragraph list items, add explicit tests
  before relying on them publicly.

