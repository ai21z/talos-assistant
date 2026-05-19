import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const read = (path) => readFileSync(join(root, path), "utf8");
const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
const publicFiles = ["index.html", "src/main.js", "src/styles.css"];
const publicText = () => publicFiles.map(read).join("\n");

function currentTalosVersion() {
  const props = readFileSync(join(root, "..", "gradle.properties"), "utf8");
  const match = props.match(/^talosVersion=(.+)$/m);
  assert.ok(match, "gradle.properties must define talosVersion");
  return match[1].trim();
}

function walkFiles(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walkFiles(path) : [path];
  });
}

function anchorTargets(html) {
  return Array.from(html.matchAll(/href="#([^"]+)"/g), (match) => match[1]);
}

function ids(html) {
  return new Set(Array.from(html.matchAll(/\sid="([^"]+)"/g), (match) => match[1]));
}

describe("Talos landing page static contract", () => {
  it("uses the final site package name and required scripts", () => {
    const pkg = JSON.parse(read("package.json"));
    assert.equal(pkg.name, "talos-site");
    assert.equal(pkg.scripts.dev, "vite");
    assert.equal(pkg.scripts.build, "vite build");
    assert.equal(pkg.scripts.preview, "vite preview");
    assert.equal(pkg.scripts.test, "npm run test:static");
    assert.equal(pkg.scripts["test:static"], "node --test test/site.test.js");
    assert.equal(pkg.scripts["test:e2e"], "playwright test");
  });

  it("keeps production source maps disabled and emits no .map files after build", () => {
    assert.match(read("vite.config.js"), /sourcemap:\s*false/);
    const mapFiles = walkFiles(join(root, "dist")).filter((file) => file.endsWith(".map"));
    assert.deepEqual(mapFiles, []);
  });

  it("uses one descriptive h1 grounded in local-first workspace identity", () => {
    const html = read("index.html");
    const h1Matches = Array.from(html.matchAll(/<h1\b[^>]*>([\s\S]*?)<\/h1>/gi));
    assert.equal(h1Matches.length, 1);
    const h1Text = h1Matches[0][1].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    assert.match(h1Text, /local-first/i);
    assert.match(h1Text, /workspace/i);
    assert.notEqual(h1Text.toUpperCase(), "TALOS");
  });

  it("uses concrete hero copy and honest CTAs", () => {
    const html = read("index.html").replace(/\s+/g, " ");
    const hero = html.slice(html.indexOf('id="product"'), html.indexOf('id="contract"'));

    for (const copy of [
      "Inspects before acting",
      "Asks before mutation",
      "Verifies before claiming success",
      "Approved writes only",
      "leaves a trace",
      "No hosted workspace handoff",
      "View on GitHub",
      "Read the execution contract",
    ]) {
      assert.match(hero, new RegExp(escapeRegExp(copy), "i"));
    }

    // Honest CTA: no placeholder-beta button must remain.
    assert.doesNotMatch(hero, /Get beta build/i);
    assert.doesNotMatch(hero, /data-beta-placeholder/i);
  });

  it("uses the locked Talos icon artifact, not the old inline SVG mark", () => {
    const html = read("index.html");
    assert.ok(existsSync(join(root, "design", "talos-icon.png")), "talos-icon.png missing");
    assert.match(html, /design\/talos-icon\.png/);
    assert.doesNotMatch(html, /data:image\/svg\+xml/i);
    assert.doesNotMatch(html, /<svg\b/i);
  });

  it("uses the locked startup terminal screenshot in the hero", () => {
    const html = read("index.html");
    assert.ok(existsSync(join(root, "design", "img.png")), "img.png missing");
    const hero = html.slice(html.indexOf('id="product"'), html.indexOf('id="contract"'));
    const heroText = hero.replace(/\s+/g, " ");
    assert.match(hero, /<img\b[^>]*class="startup-terminal-image"[^>]*src="\.\/design\/img\.png"/);
    assert.match(hero, /alt="[^"]*Talos startup terminal screen/i);
    assert.doesNotMatch(hero, /<pre\b[^>]*class="banner"/i);

    // The screenshot's textual content must remain represented for accessibility and review.
    for (const copy of [
      "TALOS",
      `v${currentTalosVersion()}`,
      "llama_cpp/gpt-oss-20b",
      "llama.cpp (managed)",
      "ready (5 chunks)",
      "ask before mutation",
    ]) {
      assert.match(heroText, new RegExp(escapeRegExp(copy)));
    }
  });

  it("ships the directional execution contract, not circular diagrams", () => {
    const html = read("index.html");
    const css = read("src/styles.css");

    // Directional contract section exists with all six steps in order.
    const contract = html.slice(html.indexOf('id="contract"'), html.indexOf('id="cli"'));
    const stepOrder = ["Classify", "Inspect", "Approve", "Mutate", "Verify", "Trace"];
    let cursor = 0;
    for (const step of stepOrder) {
      const idx = contract.indexOf(`>${step}<`, cursor);
      assert.ok(idx >= 0, `contract step "${step}" missing or out of order`);
      cursor = idx;
    }

    // The orbit/sentinel/medallion vocabulary must not return.
    for (const banned of [
      "cycle-diagram",
      "process-orbits",
      "cycle-node",
      "cycle-core",
      "sentinel-frame",
      "sentinel-emblem",
      "radial-grid",
      "footer-medallion",
      "greek-key",
    ]) {
      assert.doesNotMatch(html, new RegExp(escapeRegExp(banned), "i"), `removed class ${banned} reappeared in html`);
      assert.doesNotMatch(css, new RegExp(escapeRegExp(banned), "i"), `removed class ${banned} reappeared in css`);
    }
  });

  it("declares the trust table with allow/ask/deny state tokens", () => {
    const html = read("index.html");
    const trust = html.slice(html.indexOf('id="trust"'), html.indexOf('id="use-cases"'));
    assert.match(trust, /state--allow/);
    assert.match(trust, /state--ask/);
    assert.match(trust, /state--deny/);
    for (const surface of [
      "Workspace files (read)",
      "Protected paths (read)",
      "File writes / edits",
      "Workspace ops",
      "Command execution",
      "Private mode",
      "Unsupported binary documents",
      "Trace and prompt-debug",
    ]) {
      assert.match(trust, new RegExp(escapeRegExp(surface)));
    }
  });

  it("publishes a docs gateway with at least seven repo-linked cards", () => {
    const html = read("index.html");
    const docs = html.slice(html.indexOf('id="docs"'));
    const docCards = Array.from(docs.matchAll(/<a\s+class="doc-card[^"]*"[^>]*href="([^"]+)"/g));
    assert.ok(docCards.length >= 7, `expected ≥7 doc cards, found ${docCards.length}`);
    for (const [, href] of docCards) {
      assert.match(href, /^https:\/\/github\.com\/ai21z\/talos-cli/, `doc card href ${href} not in canonical repo`);
    }
  });

  it("keeps real command examples and canonical runtime tool names", () => {
    const text = publicText();
    for (const command of [
      "talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --write",
      "talos setup models --profile gpt-oss-20b --server-path C:/path/to/llama-server.exe --write",
      "talos status --verbose",
      "/tools",
      "/models",
      "/workspace",
      "/last trace",
      "/prompt-debug",
      "talos.list_dir",
      "talos.read_file",
      "talos.grep",
      "talos.retrieve",
      "talos.write_file",
      "talos.run_command",
      "gradle_test",
    ]) {
      assert.match(text, new RegExp(escapeRegExp(command), "i"));
    }
  });

  it("does not introduce fake downloads or unsupported claims", () => {
    const text = publicText();

    // No download attributes, no archive/install hrefs.
    assert.doesNotMatch(text, /href="[^"]*\.(?:zip|msi|exe|dmg|pkg|tar\.gz)"/i);
    assert.doesNotMatch(text, /\sdownload\s*=/i);

    // External hrefs must only target the canonical Talos repo.
    const externalHrefs = Array.from(text.matchAll(/href="(https?:\/\/[^"]+)"/g), (m) => m[1]);
    for (const href of externalHrefs) {
      assert.match(href, /^https:\/\/github\.com\/ai21z\/talos-cli/, `unexpected external href: ${href}`);
    }

    for (const misleading of [
      "swarm",
      "multi-agent",
      "autonomous workforce",
      "replaces developers",
      "one-click cloud agent",
      "AI-powered",
      "agentic",
      "browse the web",
      "Every action is verified",
      "verified write + /last trace",
      "--local-only",
      "No telemetry",
      "Get beta build",
      "Beta download placeholder",
    ]) {
      assert.doesNotMatch(text, new RegExp(escapeRegExp(misleading), "i"));
    }
  });

  it("keeps anchor navigation targetable", () => {
    const html = read("index.html");
    const definedIds = ids(html);
    for (const target of anchorTargets(html)) {
      assert.ok(definedIds.has(target), `missing #${target}`);
    }
  });

  it("labels all copy buttons uniquely and accessibly", () => {
    const html = read("index.html");
    const copyButtons = Array.from(html.matchAll(/<button\b[^>]*class="[^"]*\bcopy-button\b[^"]*"[^>]*>/g));
    assert.ok(copyButtons.length >= 5);

    const labels = copyButtons.map((match) => {
      const attr = match[0].match(/\saria-label="([^"]+)"/);
      return attr?.[1] ?? "";
    });

    assert.equal(labels.length, new Set(labels).size);
    assert.ok(labels.every((label) => /^Copy .+ command$/i.test(label)), labels.join(", "));
  });

  it("uses accessible terminal semantics", () => {
    const html = read("index.html");
    assert.doesNotMatch(html, /<pre[^>]*aria-live=/i);
    assert.match(html, /id="terminal-status"[\s\S]*aria-live="polite"/i);
    // No SVG inside aria-hidden parents should claim role="img" or aria-label.
    assert.doesNotMatch(html, /aria-hidden="true"[\s\S]{0,500}<svg[^>]*(?:role="img"|aria-label=)/i);
  });

  it("supports anchor offset and reduced motion", () => {
    const css = read("src/styles.css");
    assert.match(css, /scroll-margin-top/);
    assert.match(css, /prefers-reduced-motion:\s*reduce/);
    assert.match(css, /scroll-behavior:\s*auto\s*!important/);
    assert.match(css, /transition(?:-duration)?:\s*none\s*!important|transition-duration:\s*0\.01ms\s*!important/);
    assert.match(css, /animation(?:-duration)?:\s*none\s*!important|animation-duration:\s*0\.01ms\s*!important/);
    assert.match(css, /\.js\s+\.reveal[\s\S]*opacity:\s*1/);
    assert.doesNotMatch(css, /\.js\s+\.reveal\s*\{[\s\S]*?opacity:\s*0/);
  });

  it("uses semantic lane glyphs that match SemanticGlyphSet.java safe Unicode", () => {
    const js = read("src/main.js");
    // glyphs: bullet, arrow, success, warning, error, rail
    for (const glyph of ["•", "→", "✓", "!", "│"]) {
      assert.ok(js.includes(glyph), `lane glyph ${glyph} missing from main.js`);
    }
    for (const glyph of ["┌", "└"]) {
      assert.ok(js.includes(glyph), `answer/approval pane glyph ${glyph} missing from main.js`);
    }
    // companion-only glyph that current code does not ship
    assert.ok(!js.includes("◐"), "main.js uses ◐ which is not part of SemanticGlyphSet");
    assert.ok(!js.includes("╭"), "main.js uses rounded answer pane glyphs not shipped by SemanticGlyphSet");
    assert.ok(!js.includes("╰"), "main.js uses rounded answer pane glyphs not shipped by SemanticGlyphSet");
    assert.match(js, /approval required/);
    // canonical prompt
    assert.match(js, /talos.*\[auto\]\s*&gt;|talos.*\[auto\]\s*>/);
  });

  it("keeps vanilla JavaScript behavior for tabs and clipboard", () => {
    const js = read("src/main.js");
    assert.match(js, /terminalStates/);
    assert.match(js, /ArrowRight/);
    assert.match(js, /ArrowLeft/);
    assert.match(js, /Home/);
    assert.match(js, /End/);
    assert.match(js, /navigator\.clipboard\.writeText/);
    // Placeholder beta toast must be gone.
    assert.doesNotMatch(js, /data-beta-placeholder/);
    assert.doesNotMatch(js, /Beta download placeholder/);
    assert.doesNotMatch(js, /React|Vue|createApp|tailwind/i);
  });
});
