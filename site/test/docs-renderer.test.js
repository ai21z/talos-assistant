import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { renderMarkdown } from "../src/docs-markdown.js";

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

  it("renders Mermaid fences as visual docs diagrams instead of raw copyable code", () => {
    const html = renderMarkdown(
      [
        "```mermaid",
        "flowchart LR",
        '    Start["Classify request"] --> Tools["Narrow tools"]',
        '    Tools --> Verify["Verify result"]',
        "```",
      ].join("\n"),
    );

    assert.match(html, /class="docs-diagram docs-diagram--flowchart"/);
    assert.match(html, /Classify request/);
    assert.match(html, /Narrow tools/);
    assert.match(html, /Verify result/);
    assert.doesNotMatch(html, /docs-code/);
    assert.doesNotMatch(html, /docs-copy/);
    assert.doesNotMatch(html, /flowchart LR/);
  });

  it("renders flowchart Mermaid edges as connected paths with arrow elements", () => {
    const html = renderMarkdown(
      [
        "```mermaid",
        "flowchart LR",
        '    User["User request"] --> Contract["Task contract"]',
        '    Contract -- read-only --> Posture["Capability posture"]',
        '    Posture --> Tools["Visible tools"]',
        "```",
      ].join("\n"),
    );

    assert.match(html, /class="docs-flow-path"/);
    assert.match(html, /class="docs-flow-node"/);
    assert.match(html, /class="docs-flow-arrow"/);
    assert.match(html, /aria-label="User request to Task contract"/);
    assert.match(html, /read-only/);
    assert.match(html, /Capability posture/);
    assert.doesNotMatch(html, /docs-diagram-nodes/);
  });

  it("renders sequence Mermaid diagrams as lane-style message flows", () => {
    const html = renderMarkdown(
      [
        "```mermaid",
        "sequenceDiagram",
        "    participant U as User",
        "    participant R as REPL",
        "    participant P as Policy",
        "    U->>R: natural-language request",
        "    R->>P: derive allowed tools",
        "    P-->>U: ask for approval when required",
        "```",
      ].join("\n"),
    );

    assert.match(html, /class="docs-sequence-lanes"/);
    assert.match(html, /class="docs-sequence-actor"/);
    assert.match(html, /class="docs-sequence-arrow"/);
    assert.match(html, /User/);
    assert.match(html, /REPL/);
    assert.match(html, /Policy/);
    assert.match(html, /ask for approval when required/);
    assert.doesNotMatch(html, /sequenceDiagram/);
  });
});
