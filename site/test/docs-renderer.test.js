import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { escapeHtml, renderMarkdown, diagramMetadata } from "../src/docs-markdown.js";
import { validateDiagramSource, validateDiagramCss } from "../src/docs-diagrams.js";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
const docsDir = fileURLToPath(new URL("../../docs/architecture/", import.meta.url));

const compact = (html) => html.replace(/\s+/g, " ").trim();
const count = (html, token) => (html.match(new RegExp(token, "g")) ?? []).length;

describe("Talos docs Markdown renderer", () => {
  it("keeps indented unordered-list continuations inside the current list item", () => {
    const html = renderMarkdown(`- On Ubuntu/WSL x64, the guided setup wizard can install the pinned CPU
  \`llama.cpp\` engine and download an accepted beta model after confirmation.
- On Windows or direct/expert Linux setup, provide a local \`llama-server.exe\`
  or \`llama-server\` when configuring managed llama.cpp.`);

    assert.equal(count(html, "<li>"), 2);
    assert.equal(count(html, "<p>"), 0);
    assert.match(
      compact(html),
      /<li>On Ubuntu\/WSL x64, the guided setup wizard can install the pinned CPU <code>llama\.cpp<\/code> engine and download an accepted beta model after confirmation\.<\/li>/,
    );
    assert.match(
      compact(html),
      /<li>On Windows or direct\/expert Linux setup, provide a local <code>llama-server\.exe<\/code> or <code>llama-server<\/code> when configuring managed llama\.cpp\.<\/li>/,
    );
  });

  it("keeps indented ordered-list continuations inside the current list item", () => {
    const html = renderMarkdown(`1. Run \`talos setup wizard\` after [installation](installation.md)
   and review every planned side effect before accepting it.
2. Save the \`talos doctor --start\` output as evidence.`);

    assert.equal(count(html, "<li>"), 2);
    assert.equal(count(html, "<p>"), 0);
    assert.match(
      compact(html),
      /<li>Run <code>talos setup wizard<\/code> after <a href="#\/installation">installation<\/a> and review every planned side effect before accepting it\.<\/li>/,
    );
    assert.match(
      compact(html),
      /<li>Save the <code>talos doctor --start<\/code> output as evidence\.<\/li>/,
    );
  });

  it("preserves the complete diagram source rather than reconstructing edges", () => {
    const source = 'flowchart TB\n    accTitle: Boundary map\n    accDescr: Two domains are shown.\n    subgraph Group["Runtime"]\n    A["Isolated node"]\n    B{"Decision"} -->|yes| C["Action"]\n    end';
    const html = renderMarkdown("\x60\x60\x60mermaid\n" + source + "\n\x60\x60\x60");
    assert.match(html, /data-diagram-state="pending"/);
    assert.match(html, /<figcaption>Boundary map<\/figcaption>/);
    assert.match(html, /Two domains are shown\./);
    assert.ok(html.includes(escapeHtml(source)));
    assert.doesNotMatch(html, /docs-flow-edge|docs-diagram-step|docs-copy/);
    assert.match(html, /class="docs-diagram-expand" hidden/);
  });

  it("escapes diagram source, title and description before HTML insertion", () => {
    const html = renderMarkdown('\x60\x60\x60mermaid\nflowchart TB\naccTitle: <img src=x onerror=alert(1)>\naccDescr: <script>bad</script>\nA["</code><script>bad</script>"]\n\x60\x60\x60');
    assert.doesNotMatch(html, /<script>|<img /);
    assert.match(html, /&lt;script&gt;/);
    assert.match(html, /&lt;img/);
  });

  it("preserves sequence participants and solid and dashed messages verbatim", () => {
    const source = "sequenceDiagram\nparticipant U as User\nparticipant R as Runtime\nU->>R: Request\nR-->>U: Reply";
    const html = renderMarkdown("\x60\x60\x60mermaid\n" + source + "\n\x60\x60\x60");
    assert.ok(html.includes(escapeHtml(source)));
    assert.doesNotMatch(html, /docs-sequence-message/);
  });

  it("creates route-bound on-page links with distinct duplicate heading IDs", () => {
    const html = renderMarkdown("# Guide\n\n## Read\n\nFirst.\n\n## Read\n\nSecond.", { currentSlug: "user/guide" });
    assert.match(html, /aria-label="On this page"/);
    assert.match(html, /href="#\/user\/guide#read"/);
    assert.match(html, /href="#\/user\/guide#read-2"/);
    assert.match(html, /<h2 id="read-2">/);
    assert.doesNotMatch(renderMarkdown("# One\n\n## Only"), /docs-toc/);
  });

  it("escapes on-page labels and keeps code formatting without nested links", () => {
    const html = renderMarkdown("# Guide\n\n## A <tag>\n\n## `code`");
    assert.match(html, /A &lt;tag&gt;/);
    assert.match(html, /<code>code<\/code>/);
  });

  it("rejects configuration and interactive syntax before Mermaid is loaded", () => {
    for (const source of [
      "pie\nA: 1",
      'flowchart TB\n%%{init: {"securityLevel":"loose"}}%%\nA-->B',
      'flowchart TB\nclick A "https://example.com"',
      'flowchart TB\nstyle A fill:url(https://example.com)',
      'flowchart TB\nA --> B; style A fill:url(https://example.com)',
      'flowchart TB\nA --> B; click A callback',
      'sequenceDiagram\nparticipant A\nlink A: Link @ https://example.com',
      'sequenceDiagram\nparticipant A\nlinks A: {"Link": "https://example.com"}',
      'flowchart TB\nA@{ img: "https://example.com" }',
      "flowchart TB\n" + "A".repeat(20000),
    ]) assert.throws(() => validateDiagramSource(source));
    assert.doesNotThrow(() => validateDiagramSource('flowchart TB\nA["Safe text"] --> B'));
    assert.doesNotThrow(() => validateDiagramSource("sequenceDiagram\nA->>B: Message"));
  });

  it("supplies a title and readable description for every maintained architecture diagram", () => {
    let count = 0;
    for (const name of readdirSync(docsDir).filter((file) => file.endsWith(".md"))) {
      const md = readFileSync(join(docsDir, name), "utf8");
      for (const [, source] of md.matchAll(/\x60\x60\x60mermaid\r?\n([\s\S]*?)\x60\x60\x60/g)) {
        validateDiagramSource(source);
        const { title, description } = diagramMetadata(source);
        assert.notEqual(title, "Architecture diagram", name);
        assert.ok(description.length > 40, name);
        count++;
      }
    }
    assert.equal(count, 8);
  });

  it("permits only local SVG paint references", () => {
    for (const css of ['fill:url(#node)', 'filter:url("#shadow")', "marker-end:url( '#arrow-1' )"]) {
      assert.doesNotThrow(() => validateDiagramCss(css));
    }
    for (const css of ['fill:url(https://example.com)', 'fill:url("data:image/svg+xml,x")', '@import "x"', 'fill:u\\72l(x)', 'width:expression(x)']) {
      assert.throws(() => validateDiagramCss(css));
    }
  });
});
