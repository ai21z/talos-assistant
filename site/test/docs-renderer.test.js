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
});
