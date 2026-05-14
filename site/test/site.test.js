import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const read = (path) => readFileSync(join(root, path), "utf8");
const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const cssBlock = (css, selector) => {
  const match = css.match(new RegExp(`${escapeRegExp(selector)}\\s*\\{([^}]*)\\}`));
  return match?.[1] ?? "";
};

describe("Talos landing page", () => {
  it("defines the required Vite scripts and disables production source maps", () => {
    const pkg = JSON.parse(read("package.json"));
    assert.equal(pkg.scripts.dev, "vite");
    assert.equal(pkg.scripts.build, "vite build");
    assert.equal(pkg.scripts.preview, "vite preview");

    const viteConfig = read("vite.config.js");
    assert.match(viteConfig, /sourcemap:\s*false/);
  });

  it("is launch-ready copy, not a placeholder prototype", () => {
    const html = read("index.html");
    const js = read("src/main.js");
    const publicText = `${html}\n${js}`;

    for (const banned of [
      "Install placeholder",
      "Docs placeholder",
      "demo placeholder",
      "Placeholder",
      "prototype",
      "Launch surfaces are not wired yet",
      "No artifact is published",
    ]) {
      assert.doesNotMatch(publicText, new RegExp(escapeRegExp(banned), "i"));
    }

    assert.doesNotMatch(html, /data-placeholder="true"/);
    assert.match(html, />\s*Setup models\s*</);
    assert.match(html, />\s*View work cycle\s*</);
  });

  it("states the real beta product path and supported workflows", () => {
    const html = read("index.html").replace(/\s+/g, " ");

    for (const copy of [
      "local-first CLI workspace assistant",
      "Inspect, change, check, and trace",
      "managed llama.cpp",
      "Tested Qwen/GPT-OSS profiles",
      "approved file creation and edits",
      "approved workspace operations",
      "bounded command profiles",
      "prompt-debug evidence",
      "runtime-owned outcomes",
      "No hosted workspace handoff",
    ]) {
      assert.match(html, new RegExp(escapeRegExp(copy), "i"));
    }
  });

  it("keeps real setup commands and local model guidance visible", () => {
    const html = read("index.html");
    const js = read("src/main.js");
    const text = `${html}\n${js}`;

    for (const copy of [
      "talos setup models --profile qwen2.5-coder-14b",
      "talos setup models --profile gpt-oss-20b",
      "talos status --verbose",
      "/last trace",
      "~/.talos/models/huggingface",
      "C:/path/to/llama-server.exe",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(copy), "i"));
    }
  });

  it("includes product sections, accessible navigation, and terminal controls", () => {
    const html = read("index.html");

    for (const label of [
      "Product",
      "Work Cycle",
      "Trust",
      "Models",
      "CLI",
      "Beta",
      "What Talos does today",
      "How a Talos turn works",
      "Local model setup",
    ]) {
      assert.match(html, new RegExp(escapeRegExp(label).replace(/\s+/g, "\\s+"), "i"));
    }

    for (const tab of ["Inspect", "Approve", "Verify", "Trace"]) {
      assert.match(html, new RegExp(`role="tab"[\\s\\S]*?>\\s*${tab}\\s*</button>`));
    }

    assert.match(html, /class="skip-link"/);
    assert.match(html, /aria-label="Primary navigation"/);
    assert.match(html, /role="tabpanel"/);
  });

  it("carries the reference-inspired artifact system without becoming generic SaaS", () => {
    const html = read("index.html").replace(/\s+/g, " ");
    const css = read("src/styles.css");

    for (const copy of [
      "governed by design",
      "Contracts define intent",
      "Governed operations are checked and reported",
      "Tool turns are traceable",
      "Execution Cycle",
      "Improvement Cycle",
      "Protected workspace",
      "Traceable actions",
      "Bounded tools",
      "Local trust",
      "Bronze sentinel. Guardian logic.",
    ]) {
      assert.match(html, new RegExp(escapeRegExp(copy), "i"));
    }

    for (const cls of [
      ".artifact-shell",
      ".brand-mark",
      ".process-orbits",
      ".cycle-diagram",
      ".trust-command",
      ".ornament-rule",
      ".footer-medallion",
    ]) {
      assert.match(css, new RegExp(escapeRegExp(cls)));
    }
  });

  it("leads with product proof before brand ceremony", () => {
    const html = read("index.html");
    const css = read("src/styles.css");
    const workflowIndex = html.indexOf('id="workflow"');
    const processIndex = html.indexOf('id="process"');
    const heroTerminalIndex = html.indexOf('class="terminal terminal--hero"');
    const sentinelIndex = html.indexOf('class="sentinel-frame"');

    assert.ok(workflowIndex > -1, "workflow section should exist");
    assert.ok(processIndex > -1, "process section should exist");
    assert.ok(workflowIndex < processIndex, "workflow proof should appear before process ceremony");
    assert.ok(heroTerminalIndex > -1, "hero terminal should exist");
    assert.ok(sentinelIndex > -1, "sentinel visual should exist");
    assert.ok(heroTerminalIndex < sentinelIndex, "terminal proof should precede sentinel art in hero markup");

    for (const copy of [
      "Task receipt",
      "Read / Approve / Mutate / Check",
      "Files are read-only until approval",
      "Approval is required before mutation",
      "What Talos does today",
    ]) {
      assert.match(html, new RegExp(escapeRegExp(copy), "i"));
    }

    for (const cls of [".hero-product-panel", ".proof-ledger", ".task-receipt"]) {
      assert.match(css, new RegExp(escapeRegExp(cls)));
    }
  });

  it("keeps the hero compact enough to reveal the next section cue", () => {
    const css = read("src/styles.css");
    const hero = cssBlock(css, ".hero-section");
    const heroReveal = cssBlock(css, ".js .hero-section .reveal");
    const panel = cssBlock(css, ".hero-product-panel");
    const terminal = cssBlock(css, ".terminal--hero");
    const sentinel = cssBlock(css, ".sentinel-frame");

    assert.match(hero, /padding-top:\s*2\.[0-9]+rem/);
    assert.match(hero, /padding-bottom:\s*(?:0|1)\.[0-9]+rem/);
    assert.match(heroReveal, /opacity:\s*1/);
    assert.match(heroReveal, /transform:\s*none/);
    assert.match(panel, /grid-template-areas/);
    assert.doesNotMatch(terminal, /position:\s*absolute/);
    assert.doesNotMatch(sentinel, /position:\s*absolute/);
  });

  it("states trust boundaries explicitly and truthfully", () => {
    const html = read("index.html");
    const css = read("src/styles.css");

    for (const copy of [
      "Trust boundaries",
      "Workspace files",
      "Read-only inspection first",
      "File writes",
      "Preview and approval required",
      "Workspace operations",
      "Approved before mkdir, copy, move, rename, or delete",
      "Command profiles",
      "Bounded and approved before execution",
      "Unsupported binary documents",
      "Refused instead of faked",
    ]) {
      assert.match(html, new RegExp(escapeRegExp(copy), "i"));
    }

    for (const cls of [".boundary-grid", ".boundary-row", ".boundary-state"]) {
      assert.match(css, new RegExp(escapeRegExp(cls)));
    }
  });

  it("keeps public examples aligned with canonical Talos runtime names", () => {
    const html = read("index.html");
    const js = read("src/main.js");
    const text = `${html}\n${js}`;

    for (const canonical of [
      "talos.grep",
      "talos.retrieve",
      "talos.write_file",
      "talos.run_command",
      "gradle_test",
      "write/readback result + /last trace",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(canonical), "i"));
    }

    for (const misleading of [
      "retrieve_context",
      "gradle-test",
      "Every action is verified",
      "verified write + /last trace",
      "Diff and approval required",
      "--local-only",
    ]) {
      assert.doesNotMatch(text, new RegExp(escapeRegExp(misleading), "i"));
    }
  });

  it("implements tab switching and real command copy behavior", () => {
    const js = read("src/main.js");
    assert.match(js, /terminalStates/);
    assert.match(js, /navigator\.clipboard\.writeText/);
    assert.match(js, /aria-selected/);
    assert.match(js, /document\.documentElement\.classList\.add\("js"\)/);
    assert.match(js, /Command copied\./);
    assert.doesNotMatch(js, /placeholder/i);
  });

  it("defines the reusable CSS class vocabulary and reduced motion behavior", () => {
    const css = read("src/styles.css");
    const reveal = cssBlock(css, ".js .reveal");
    for (const token of [
      "--bg",
      "--bg-elevated",
      "--text",
      "--muted",
      "--bronze",
      "--bronze-soft",
      "--cyan",
      "--border",
      "--shadow",
      "--radius",
      "--max-width",
      ".container",
      ".section",
      ".eyebrow",
      ".button",
      ".button--primary",
      ".button--ghost",
      ".terminal",
      ".terminal-tabs",
      ".status-chip",
      ".feature-card",
      ".timeline-step",
    ]) {
      assert.match(css, new RegExp(escapeRegExp(token)));
    }
    assert.match(css, /prefers-reduced-motion/);
    assert.match(css, /\.js\s+\.reveal/);
    assert.match(reveal, /opacity:\s*1/);
    assert.match(reveal, /transform:\s*none/);
  });
});
